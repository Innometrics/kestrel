package com.twitter.scarling

import java.net.InetSocketAddress
import java.nio.ByteOrder
import scala.actors.Actor
import scala.actors.Actor._
import scala.collection.mutable
import net.lag.configgy.StringUtils
import net.lag.logging.Logger
import org.apache.mina.common._
import org.apache.mina.transport.socket.nio.SocketSessionConfig


object ScarlingHandler {
    private var _nextSessionID: Int = 0
    private var _sessions: Int = 0
    private var _totalConnections: Int = 0
    private var _getRequests: Int = 0
    private var _setRequests: Int = 0
    
    def nextSessionID = synchronized {
        _nextSessionID += 1
        _nextSessionID
    }
    
    def addSession = synchronized {
        _sessions += 1
        _totalConnections += 1
    }
    
    def removeSession = synchronized {
        _sessions -= 1
    }
    
    def addGetRequest = synchronized {
        _getRequests += 1
    }
    
    def addSetRequest = synchronized {
        _setRequests += 1
    }
    
    def sessionCount = synchronized { _sessions }
    def totalConnectionCount = synchronized { _totalConnections }
    def getRequests = synchronized { _getRequests }
    def setRequests = synchronized { _setRequests }
}


class ScarlingHandler(val session: IoSession) extends Actor {
    private val log = Logger.get
    
    private val IDLE_TIMEOUT = 60
    private val sessionID = ScarlingHandler.nextSessionID
    private val remoteAddress = session.getRemoteAddress.asInstanceOf[InetSocketAddress]
    

	if (session.getTransportType == TransportType.SOCKET) {
		session.getConfig.asInstanceOf[SocketSessionConfig].setReceiveBufferSize(2048)
	}
    session.setIdleTime(IdleStatus.BOTH_IDLE, IDLE_TIMEOUT)
    ScarlingHandler.addSession
    log.debug("New session %d from %s:%d", sessionID, remoteAddress.getHostName, remoteAddress.getPort)
    start
    
    def act = {
        loop {
            react {
                case MinaMessage.MessageReceived(msg) => handle(msg.asInstanceOf[memcache.Request])
                
                case MinaMessage.ExceptionCaught(cause) => {
                    log.error("Exception caught on session %d: %s", sessionID, cause.getMessage)
                    writeResponse("ERROR\r\n")
                    session.close
                }
                
                case MinaMessage.SessionClosed => {
                    log.debug("End of session %d", sessionID)
                    ScarlingHandler.removeSession
                    exit()
                }
                
                case MinaMessage.SessionIdle(status) => {
                    log.debug("Idle timeout on session %s", session)
                    session.close
                }
            }
        }
    }
    
    private def writeResponse(out: String) = {
        val bytes = out.getBytes
        session.write(new memcache.Response(ByteBuffer.wrap(bytes)))
    }
    
    private def writeResponse(out: String, data: Array[Byte]) = {
        val bytes = out.getBytes
        val buffer = ByteBuffer.allocate(bytes.length + data.length + 7)
        buffer.put(bytes)
        buffer.put(data)
        buffer.put("\r\nEND\r\n".getBytes)
        buffer.flip
        Scarling.addBytesWritten(buffer.capacity)
        session.write(new memcache.Response(buffer))
    }
    
    private def handle(request: memcache.Request) = {
        request.line(0) match {
            case "GET" => get(request.line(1))
            case "SET" => set(request.line(1), request.line(2).toInt, request.line(3).toInt, request.data.get)
            case "STATS" => stats
            case "SHUTDOWN" => shutdown
        }
    }
    
    private def get(name: String): Unit = {
        ScarlingHandler.addGetRequest
        val now = (System.currentTimeMillis / 1000).toInt
        Scarling.queues.remove(name) match {
            case None => writeResponse("END\r\n")
            case Some(item) => {
                val (expiry, data) = unpack(item)
                if ((expiry == 0) || (expiry >= now)) {
                    writeResponse("VALUE " + name + " 0 " + data.length + "\r\n", data)
                } else {
                    Scarling.addExpiry(name)
                    get(name)
                }
            }
        }
    }
    
    private def set(name: String, flags: Int, expiry: Int, data: Array[Byte]) = {
        ScarlingHandler.addSetRequest
        if (Scarling.queues.add(name, pack(expiry, data))) {
            writeResponse("STORED\r\n")
        } else {
            writeResponse("NOT_STORED\r\n")
        }
    }
    
    private def stats = {
        var report = new mutable.ArrayBuffer[(String, String)]
        report += (("uptime", Scarling.uptime.toString))
        report += (("time", (System.currentTimeMillis / 1000).toString))
        report += (("version", Scarling.VERSION))
        report += (("curr_items", Scarling.queues.currentItems.toString))
        report += (("total_items", Scarling.queues.totalAdded.toString))
        report += (("bytes", Scarling.queues.currentBytes.toString))
        report += (("curr_connections", ScarlingHandler.sessionCount.toString))
        report += (("total_connections", ScarlingHandler.totalConnectionCount.toString))
        report += (("cmd_get", ScarlingHandler.getRequests.toString))
        report += (("cmd_set", ScarlingHandler.setRequests.toString))
        report += (("get_hits", Scarling.queues.queueHits.toString))
        report += (("get_misses", Scarling.queues.queueMisses.toString))
        report += (("bytes_read", Scarling.bytesRead.toString))
        report += (("bytes_written", Scarling.bytesWritten.toString))
        report += (("limit_maxbytes", "0"))                         // ???
        
        for (qName <- Scarling.queues.queues) {
            val (size, bytes, totalItems, journalSize) = Scarling.queues.stats(qName)
            report += (("queue_" + qName + "_items", size.toString))
            report += (("queue_" + qName + "_total_items", totalItems.toString))
            report += (("queue_" + qName + "_logsize", journalSize.toString))
            report += (("queue_" + qName + "_expired_items", Scarling.expiryStats(qName).toString))
        }
        
        val summary = (for (item <- report) yield StringUtils.format("STAT %s %s", item._1, item._2)).mkString("", "\r\n", "\r\nEND\r\n")
        writeResponse(summary)
    }
    
    private def shutdown = {
        Scarling.shutdown
    }
    
    private def pack(expiry: Int, data: Array[Byte]): Array[Byte] = {
        val bytes = new Array[Byte](data.length + 4)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(expiry)
        buffer.put(data)
        bytes
    }
    
    private def unpack(data: Array[Byte]): (Int, Array[Byte]) = {
        val buffer = ByteBuffer.wrap(data)
        val bytes = new Array[Byte](data.length - 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val expiry = buffer.getInt
        buffer.get(bytes)
        return (expiry, bytes)
    }
}