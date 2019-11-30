package korolev.http

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

import korolev.data.ByteVector
import korolev.effect.{Queue, Stream}
import korolev.http.protocol.WebSocketProtocol.Frame
import korolev.web.Request.Method
import korolev.web.Response.Status
import korolev.web.{Headers, Path, Request}
import org.scalatest.{AsyncFlatSpec, Matchers}

import scala.concurrent.Future

class HttpClientSpec extends AsyncFlatSpec with Matchers {

  "HttpClient" should "properly send GET (content-length: 0) requests" in {
    for {
      response <- HttpClient(
        host = "example.com",
        port = 80,
        request = Request(Method.Get, Path.Root, Nil, Some(0), Stream.empty[Future, ByteVector])
      )
      strictResponseBody <- response.body.fold(ByteVector.empty)(_ ++ _)
      utf8Body = strictResponseBody.utf8String
    } yield {
      assert(utf8Body.contains("Example Domain") && response.status == Status.Ok)
    }
  }

  def uncompressByteArray(from: ByteVector): ByteVector = {
    val stream = new GZIPInputStream(new ByteArrayInputStream(from.mkArray), from.length.toInt)
    ByteVector(stream.readAllBytes())
  }

  it should "receive chunked bodies well" in {
    for {
      response <- HttpClient(
        host = "todomvc.com",
        port = 80,
        request = Request(
          Method.Get,
          Path.Root / "examples" / "react" / "node_modules" / "todomvc-common" / "base.css",
          Vector(Headers.AcceptEncoding -> "gzip"),
          Some(0),
          Stream.empty[Future, ByteVector]
        )
      )
      strictResponseBody <- response.body.fold(ByteVector.empty)(_ ++ _)
      utf8Body = uncompressByteArray(strictResponseBody).utf8String
    } yield {
      println(response)
      assert(utf8Body.contains("@media") && response.status == Status.Ok)
    }
  }

  final val wsSample1 = Frame.Text(ByteVector.ascii("Hello!"))

  final val wsSample2 = Frame.Text(ByteVector.ascii("I'm cow!"))

  it should "properly send/receive WebSocket frames" in {
    for {
      queue <- Future.successful(Queue[Future, Frame]())
      response <- HttpClient.webSocket(
        host = "echo.websocket.org",
        port = 80,
        path = Path.Root,
        outgoingFrames = queue.stream
      )
      _ <- queue.offer(wsSample1)
      echo1 <- response.body.pull()
      _ <- queue.offer(wsSample2)
      echo2 <- response.body.pull()
      _ <- response.body.cancel()
      _ <- queue.close()
    } yield {
      assert(echo1.contains(wsSample1) && echo2.contains(wsSample2))
    }
  }
}
