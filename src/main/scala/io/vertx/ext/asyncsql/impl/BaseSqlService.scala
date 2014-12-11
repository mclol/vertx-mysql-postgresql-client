package io.vertx.ext.asyncsql.impl

import java.util.UUID

import com.github.mauricio.async.db.{Configuration, Connection}
import io.netty.channel.EventLoop
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.Logger
import io.vertx.core.logging.impl.LoggerFactory
import io.vertx.core.{AsyncResult, Handler, Vertx, Future => VFuture}
import io.vertx.ext.asyncsql.impl.pool.{AsyncConnectionPool, SimpleExecutionContext}

import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * @author <a href="http://www.campudus.com">Joern Bernhardt</a>.
 */
trait BaseSqlService[ConnectionType, TransactionType, PoolType <: AsyncConnectionPool] extends CommandImplementations {

  val vertx: Vertx
  val config: JsonObject

  protected final val logger: Logger = LoggerFactory.getLogger(super.getClass)
  protected implicit val executionContext: ExecutionContext = SimpleExecutionContext(logger)

  protected def defaultHost: String

  protected def defaultPort: Int

  protected def defaultDatabase: Option[String]

  protected def defaultUser: String

  protected def defaultPassword: Option[String]

  protected val poolFactory: (Vertx, Configuration, EventLoop, Int) => PoolType

  protected lazy val maxPoolSize: Integer = config.getInteger("maxPoolSize", 10)
  protected lazy val transactionTimeout: Integer = config.getInteger("transactionTimeout", 500)
  protected lazy val configuration: Configuration = getConfiguration(config)
  protected lazy val pool: AsyncConnectionPool = AsyncConnectionPool(vertx, maxPoolSize, configuration, poolFactory)
  protected lazy val registerAddress: String = config.getString("address")

  protected def createTransactionProxy(connection: Connection, freeHandler: Connection => Future[_]): TransactionType

  protected def createConnectionProxy(connection: Connection, freeHandler: Connection => Future[_]): ConnectionType

  protected def withConnection[T](fn: Connection => Future[T]): Future[T] = {
    pool.withConnection(fn)
  }

  def begin(handler: Handler[AsyncResult[TransactionType]]): Unit =
    usePoolForInnerProxy(createTransactionProxy, handler)

  def take(handler: Handler[AsyncResult[ConnectionType]]): Unit =
    usePoolForInnerProxy(createConnectionProxy, handler)

  def stop(stopped: Handler[AsyncResult[Void]]): Unit = {
    pool.close() onComplete {
      case Success(p) => stopped.handle(VFuture.succeededFuture())
      case Failure(ex) => stopped.handle(VFuture.failedFuture(ex))
    }
  }

  def start(started: Handler[AsyncResult[Void]]): Unit = {
    logger.info(s"starting ${this.getClass.getName}")
    started.handle(VFuture.succeededFuture())
  }

  private def usePoolForInnerProxy[T](createFn: (Connection, Connection => Future[_]) => T, handler: Handler[AsyncResult[T]]): Unit = {
    pool.take().onComplete {
      case Success(conn) =>
        val freeHandler = (conn: Connection) => pool.giveBack(conn)
        val proxy = createFn(conn, freeHandler)
        handler.handle(VFuture.succeededFuture(proxy))
      case Failure(ex) =>
        handler.handle(VFuture.failedFuture(ex))
    }
  }

  private def getConfiguration(config: JsonObject) = {
    val host = config.getString("host", defaultHost)
    val port = config.getInteger("port", defaultPort)
    val username = config.getString("username", defaultUser)
    val password = Option(config.getString("password")).orElse(defaultPassword)
    val database = Option(config.getString("database")).orElse(defaultDatabase)

    logger.info(s"host=$host, defaultHost=$defaultHost")
    Configuration(username, host, port, password, database)
  }

}