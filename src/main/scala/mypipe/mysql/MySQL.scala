package mypipe.mysql

import com.github.shyiko.mysql.binlog.BinaryLogClient.{ LifecycleListener, EventListener }
import com.github.shyiko.mysql.binlog.BinaryLogClient
import com.github.shyiko.mysql.binlog.event.EventType._
import com.github.shyiko.mysql.binlog.event._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import mypipe.Conf
import mypipe.Log
import mypipe.api._
import mypipe.api.UpdateMutation
import mypipe.api.DeleteMutation
import mypipe.api.InsertMutation
import com.github.mauricio.async.db.{ RowData, QueryResult, Connection, Configuration }
import com.github.mauricio.async.db.mysql.MySQLConnection
import scala.concurrent.{ Future, Await }
import akka.actor.ActorSystem

import com.foundationdb.sql.parser.{ ColumnDefinitionNode, Visitable, SQLParser, Visitor }

case class BinlogFilePos(filename: String, pos: Long) {
  override def toString(): String = s"$filename:$pos"
}

object BinlogFilePos {
  val current = BinlogFilePos("", 0)
}

case class BinlogConsumer(hostname: String, port: Int, username: String, password: String, binlogFileAndPos: BinlogFilePos) {

  val tablesById = scala.collection.mutable.HashMap[Long, Table]()
  var transactionInProgress = false
  val groupEventsByTx = Conf.GROUP_EVENTS_BY_TX
  val producers = new scala.collection.mutable.HashSet[Producer]()
  val txQueue = new scala.collection.mutable.ListBuffer[Event]
  val client = new BinaryLogClient(hostname, port, username, password)

  client.registerEventListener(new EventListener() {

    override def onEvent(event: Event) {

      val eventType = event.getHeader().asInstanceOf[EventHeader].getEventType()

      eventType match {
        case TABLE_MAP ⇒ {
          val tableMapEventData: TableMapEventData = event.getData();

          if (!tablesById.contains(tableMapEventData.getTableId)) {
            val columns = getColumns(tableMapEventData.getDatabase(), tableMapEventData.getTable())
            val table = Table(tableMapEventData.getTableId(), tableMapEventData.getTable(), tableMapEventData.getDatabase(), tableMapEventData, columns)
            tablesById.put(tableMapEventData.getTableId(), table)
          }
        }

        case e: EventType if isMutation(eventType) == true ⇒ {
          if (groupEventsByTx) {
            txQueue += event
          } else {
            producers foreach (p ⇒ p.queue(createMutation(event)))
          }
        }

        case QUERY ⇒ {
          if (groupEventsByTx) {
            val queryEventData: QueryEventData = event.getData()
            val query = queryEventData.getSql()
            if (groupEventsByTx) {
              if ("BEGIN".equals(query)) {
                transactionInProgress = true
              } else if ("COMMIT".equals(query)) {
                commit()
              } else if ("ROLLBACK".equals(query)) {
                rollback()
              }
            }
          }
        }
        case XID ⇒ {
          if (groupEventsByTx) {
            commit()
          }
        }
        case _ ⇒ Log.finer(s"Event ignored ${eventType}")
      }
    }

    val dbConns = scala.collection.mutable.HashMap[String, Connection]()
    val dbTableCols = scala.collection.mutable.HashMap[String, List[Column]]()
    implicit val ec = ActorSystem("mypipe").dispatcher

    def getColumns(db: String, table: String): List[Column] = {

      val cols = dbTableCols.getOrElseUpdate(s"$db.$table", {
        val dbConn = dbConns.getOrElseUpdate(db, {
          val configuration = new Configuration(username, hostname, port, Some(password), Some(db))
          val connection: Connection = new MySQLConnection(configuration)
          Await.result(connection.connect, 5 seconds)
          connection
        })

        val future: Future[QueryResult] = dbConn.sendQuery(s"SHOW CREATE TABLE $db.$table")

        val mapResult: Future[String] = future.map(queryResult ⇒ queryResult.rows match {
          case Some(resultSet) ⇒ {
            val row: RowData = resultSet.head
            row(1).asInstanceOf[String]
          }

          case None ⇒ ""
        })

        val result = Await.result(mapResult, 5 seconds)
        parseCreateTable(result)
      })

      cols
    }

    def parseCreateTable(str: String): List[Column] = {
      val parser = new SQLParser
      val s = parser.parseStatement(str.replaceAll("""\(\d+\)""", "").replaceAll("AUTO_INCREMENT", "").replaceAll("""\) ENGINE.+""", ")"))
      val cols = scala.collection.mutable.ListBuffer[Column]()

      val v = new Visitor() {
        override def skipChildren(node: Visitable): Boolean = false
        override def stopTraversal(): Boolean = false
        override def visitChildrenFirst(node: Visitable): Boolean = false
        override def visit(node: Visitable): Visitable = {

          node match {
            case n: ColumnDefinitionNode ⇒ {
              cols += Column(n.getColumnName)
            }

            case _ ⇒
          }

          node
        }
      }

      s.accept(v)
      cols.toList
    }

    def rollback() {
      txQueue.clear
      transactionInProgress = false
    }

    def commit() {
      val mutations = txQueue.map(createMutation(_))
      producers foreach (p ⇒ p.queueList(mutations.toList))
      txQueue.clear
      transactionInProgress = false
    }
  })

  if (binlogFileAndPos != BinlogFilePos.current) {
    Log.info(s"Resuming binlog consumption from file=${binlogFileAndPos.filename} pos=${binlogFileAndPos.pos} for $hostname:$port")
    client.setBinlogFilename(binlogFileAndPos.filename)
    client.setBinlogPosition(binlogFileAndPos.pos)
  } else {
    Log.info(s"Using current master binlog position for consuming from $hostname:$port")
  }

  client.registerLifecycleListener(new LifecycleListener {
    override def onDisconnect(client: BinaryLogClient): Unit = {
      Conf.binlogFilePosSave(hostname, port, BinlogFilePos(client.getBinlogFilename, client.getBinlogPosition))
    }

    override def onEventDeserializationFailure(client: BinaryLogClient, ex: Exception) {}
    override def onConnect(client: BinaryLogClient) {}
    override def onCommunicationFailure(client: BinaryLogClient, ex: Exception) {}
  })

  def connect() {
    client.connect()
  }

  def disconnect() {
    client.disconnect()
    producers foreach (p ⇒ p.flush)
  }

  def registerProducer(producer: Producer) {
    producers += producer
  }

  def isMutation(eventType: EventType): Boolean = eventType match {
    case PRE_GA_WRITE_ROWS | WRITE_ROWS | EXT_WRITE_ROWS |
      PRE_GA_UPDATE_ROWS | UPDATE_ROWS | EXT_UPDATE_ROWS |
      PRE_GA_DELETE_ROWS | DELETE_ROWS | EXT_DELETE_ROWS ⇒ true
    case _ ⇒ false
  }

  def createMutation(event: Event): Mutation[_] = event.getHeader().asInstanceOf[EventHeader].getEventType() match {
    case PRE_GA_WRITE_ROWS | WRITE_ROWS | EXT_WRITE_ROWS ⇒ {
      val evData = event.getData[WriteRowsEventData]()
      val table = tablesById.get(evData.getTableId).get
      InsertMutation(table, evData.getRows().asScala.toList)
    }

    case PRE_GA_UPDATE_ROWS | UPDATE_ROWS | EXT_UPDATE_ROWS ⇒ {
      val evData = event.getData[UpdateRowsEventData]()
      val table = tablesById.get(evData.getTableId).get
      val rows = evData.getRows().asScala.toList.map(row ⇒ {
        (row.getKey, row.getValue)
      })

      UpdateMutation(table, rows)
    }
    case PRE_GA_DELETE_ROWS | DELETE_ROWS | EXT_DELETE_ROWS ⇒ {
      val evData = event.getData[DeleteRowsEventData]()
      val table = tablesById.get(evData.getTableId).get
      DeleteMutation(table, evData.getRows().asScala.toList)
    }
  }
}

class HostPortUserPass(val host: String, val port: Int, val user: String, val password: String)
object HostPortUserPass {

  def apply(hostPortUserPass: String) = {
    val params = hostPortUserPass.split(":")
    new HostPortUserPass(params(0), params(1).toInt, params(2), params(3))
  }
}

