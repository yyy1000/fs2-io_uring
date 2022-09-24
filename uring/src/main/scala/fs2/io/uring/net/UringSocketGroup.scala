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
package uring
package net

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.syntax.all._
import com.comcast.ip4s.Dns
import com.comcast.ip4s.Host
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Ipv4Address
import com.comcast.ip4s.Port
import com.comcast.ip4s.SocketAddress
import fs2.io.net.Socket
import fs2.io.net.SocketGroup
import fs2.io.net.SocketOption
import fs2.io.uring.unsafe.uring._

import scala.scalanative.posix.sys.socket._

private final class UringSocketGroup[F[_]](implicit F: Async[F], dns: Dns[F])
    extends SocketGroup[F] {

  def client(to: SocketAddress[Host], options: List[SocketOption]): Resource[F, Socket[F]] =
    Resource.eval(Uring[F]).flatMap { implicit ring =>
      Resource.eval(to.resolve).flatMap { address =>
        openSocket(address.host.isInstanceOf[Ipv4Address]).evalMap { fd =>
          ring { sqe =>
            SocketAddressHelpers.toSockaddr(address)(
              io_uring_prep_connect(sqe, fd, _, _)
            )
          } *> UringSocket(ring, fd, address)
        }
      }
    }

  def server(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Stream[F, Socket[F]] = Stream.resource(serverResource(address, port, options)).flatMap(_._2)

  def serverResource(
      address: Option[Host],
      port: Option[Port],
      options: List[SocketOption]
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] = ???

  private def openSocket(ipv4: Boolean)(implicit ring: Uring[F]): Resource[F, Int] =
    Resource.make[F, Int] {
      val domain = if (ipv4) AF_INET else AF_INET6
      F.delay(socket(domain, SOCK_STREAM, 0))
    } { fd =>
      ring(io_uring_prep_close(_, fd)).void
    }

}

object UringSocketGroup {

  def apply[F[_]](implicit F: Async[F]): SocketGroup[F] = new UringSocketGroup

}
