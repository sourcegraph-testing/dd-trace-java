/*
 * Copyright The OpenTelemetry Authors
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

package server

import io.opentelemetry.auto.instrumentation.api.MoreAttributes
import io.opentelemetry.auto.test.asserts.TraceAssert
import io.opentelemetry.auto.test.base.HttpServerTest
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.trace.attributes.SemanticAttributes
import play.api.test.TestServer

import static io.opentelemetry.auto.test.base.HttpServerTest.ServerEndpoint.ERROR
import static io.opentelemetry.auto.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static io.opentelemetry.auto.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static io.opentelemetry.auto.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static io.opentelemetry.trace.Span.Kind.INTERNAL

class PlayServerTest extends HttpServerTest<TestServer> {
  @Override
  TestServer startServer(int port) {
    def server = SyncServer.server(port)
    server.start()
    return server
  }

  @Override
  void stopServer(TestServer server) {
    server.stop()
  }

  // We don't have instrumentation for this version of netty yet
  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  void handlerSpan(TraceAssert trace, int index, Object parent, String method = "GET", ServerEndpoint endpoint = SUCCESS) {
    trace.span(index) {
      operationName "play.request"
      spanKind INTERNAL
      errored endpoint == ERROR || endpoint == EXCEPTION || endpoint == NOT_FOUND
      childOf((SpanData) parent)
      attributes {
        "${SemanticAttributes.NET_PEER_IP.key()}" { it == null || it == "127.0.0.1" } // Optional
        "${SemanticAttributes.HTTP_URL.key()}" String
        "${SemanticAttributes.HTTP_METHOD.key()}" String
        "${SemanticAttributes.HTTP_STATUS_CODE.key()}" Long
        if (endpoint == EXCEPTION) {
          errorAttributes(Exception, EXCEPTION.body)
        }
        if (endpoint.query) {
          "$MoreAttributes.HTTP_QUERY" endpoint.query
        }
      }
    }
  }

  @Override
  String expectedOperationName(String method, ServerEndpoint endpoint) {
    return "netty.request"
  }
}