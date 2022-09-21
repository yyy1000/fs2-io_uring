/*
 * Copyright 2022 Arman Bilge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2
package io
package uring.net

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.effect.std.Semaphore
import cats.syntax.all._
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Ipv4Address
import com.comcast.ip4s.Ipv6Address
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.Pipe
import fs2.io.net.Socket
import fs2.io.uring.unsafe.util._

import java.io.IOException
import scala.scalanative.libc.errno._
import scala.scalanative.posix.arpa.inet._
import scala.scalanative.posix.netinet.in._
import scala.scalanative.posix.netinet.inOps._
import scala.scalanative.posix.sys.socket._
import scala.scalanative.posix.sys.socketOps._
import scala.scalanative.unsafe._

private[net] final class UringSocket[F[_]](
    fd: Int,
    remoteAddress: SocketAddress[IpAddress],
    defaultReadSize: Int,
    readSemaphore: Semaphore[F],
    writeSemaphore: Semaphore[F]
)(implicit F: Async[F])
    extends Socket[F] {

  private[this] def readImpl(bytes: Ptr[Byte], maxBytes: Int): F[Int] =
    ???

  def read(maxBytes: Int): F[Option[Chunk[Byte]]] =
    readSemaphore.permit.surround {
      for {
        bytes <- F.delay(new Array[Byte](maxBytes))
        readed <- readImpl(toPtr(bytes), maxBytes)
      } yield Option.when(readed > 0)(Chunk.array(bytes, 0, readed))
    }

  def readN(numBytes: Int): F[Chunk[Byte]] =
    readSemaphore.permit.surround {
      F.delay(new Array[Byte](numBytes)).flatMap { bytes =>
        val ptr = toPtr(bytes)

        def go(i: Int): F[Int] = {
          val remaining = numBytes - i
          readImpl(ptr + i.toLong, remaining).flatMap {
            case 0           => F.pure(i)
            case `remaining` => F.pure(numBytes)
            case readed      => go(i + readed)
          }
        }

        go(0).map(Chunk.array(bytes, 0, _))
      }
    }

  def reads: Stream[F, Byte] = Stream.repeatEval(read(defaultReadSize)).unNoneTerminate.unchunks

  def endOfInput: F[Unit] = ???

  def endOfOutput: F[Unit] = ???

  def isOpen: F[Boolean] = F.pure(true)

  def remoteAddress: F[SocketAddress[IpAddress]] = F.pure(remoteAddress)

  def localAddress: F[SocketAddress[IpAddress]] = UringSocket.getLocalAddress(fd)

  def write(bytes: Chunk[Byte]): F[Unit] =
    writeSemaphore.permit.surround(F.unit)

  def writes: Pipe[F, Byte, Nothing] = _.chunks.foreach(write)

}

private[net] object UringSocket {

  def getLocalAddress[F[_]](fd: Int)(implicit F: Sync[F]): F[SocketAddress[IpAddress]] =
    F.delay {
      val addr = // allocate enough for an IPv6
        stackalloc[sockaddr_in6]().asInstanceOf[Ptr[sockaddr]]
      val len = stackalloc[socklen_t]()
      !len = sizeof[sockaddr_in6].toUInt
      if (getsockname(fd, addr, len) == -1)
        F.raiseError(new IOException(s"getsockname: ${errno}"))
      else if (addr.sa_family == AF_INET)
        F.pure(toIpv4SocketAddress(addr.asInstanceOf[Ptr[sockaddr_in]]))
      else if (addr.sa_family == AF_INET6)
        F.pure(toIpv6SocketAddress(addr.asInstanceOf[Ptr[sockaddr_in6]]))
      else
        F.raiseError(new IOException(s"Unsupported sa_family: ${addr.sa_family}"))
    }.flatten
      .widen

  private[this] def toIpv4SocketAddress(addr: Ptr[sockaddr_in]): SocketAddress[Ipv4Address] = {
    val port = Port.fromInt(ntohs(addr.sin_port).toInt).get
    val addrBytes = addr.sin_addr.at1.asInstanceOf[Ptr[Byte]]
    val host = Ipv4Address.fromBytes(
      addrBytes(0).toInt,
      addrBytes(1).toInt,
      addrBytes(2).toInt,
      addrBytes(3).toInt
    )
    SocketAddress(host, port)
  }

  private[this] def toIpv6SocketAddress(addr: Ptr[sockaddr_in6]): SocketAddress[Ipv6Address] = {
    val port = Port.fromInt(ntohs(addr.sin6_port).toInt).get
    val addrBytes = addr.sin6_addr.at1.asInstanceOf[Ptr[Byte]]
    val host = Ipv6Address.fromBytes {
      val addr = new Array[Byte](16)
      var i = 0
      while (i < addr.length) {
        addr(i) = addrBytes(i.toLong)
        i += 1
      }
      addr
    }.get
    SocketAddress(host, port)
  }

}
