/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.http

import ratpack.http.client.RequestSpec
import ratpack.test.internal.RatpackGroovyDslSpec
import ratpack.test.internal.SimpleErrorHandler

class RequestBodyReadingSpec extends RatpackGroovyDslSpec {

  def "can get request body as bytes"() {
    when:
    handlers {
      post {
        request.body.then { body ->
          response.send new String(body.bytes, "utf8")
        }
      }
    }

    then:
    requestSpec { RequestSpec requestSpec -> requestSpec.body.stream({ it << "foo" }) }
    postText() == "foo"
  }

  def "can get request body as input stream"() {
    when:
    handlers {
      post {
        request.body.then { body ->
          response.send new String(body.inputStream.bytes, "utf8")
        }
      }
    }

    then:
    requestSpec { it.body.stream { it << "foo".getBytes("utf8") } }
    postText() == "foo"
  }

  def "can get large request body as bytes"() {
    given:
    def string = "a" * 1024 * 9

    when:
    handlers {
      post {
        request.body.then { body ->
          response.send new String(body.bytes, "utf8")
        }
      }
    }

    then:
    requestSpec { requestSpec ->
      requestSpec.body.stream({ it << string.getBytes("utf8") })
      postText() == string
    }
  }

  def "get bytes on get request"() {
    when:
    handlers {
      all {
        request.body.then { body ->
          response.send body.bytes.length.toString()
        }
      }
    }

    then:
    getText() == "0"
    postText() == "0"
    putText() == "0"
  }

  def "can eagerly release body"() {
    when:
    handlers {
      post {
        request.body.then { it.buffer.release(); render "ok" }
      }
    }

    then:
    requestSpec {
      it.body.text("foo")
    }
    postText() == "ok"
  }

  def "can't read body twice"() {
    when:
    bindings {
      bind SimpleErrorHandler
    }
    handlers {
      post {
        request.body.then { 1 }
        request.body.then { render "ok" }
      }
    }

    then:
    requestSpec { it.body.text("foo") }
    postText() == "ratpack.http.RequestBodyAlreadyReadException: the request body has already been read"
  }
}
