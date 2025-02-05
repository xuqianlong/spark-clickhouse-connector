/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xenon.clickhouse

import java.io.{File, InputStream}
import java.net.URI
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.LockSupport

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try, Using}

import org.apache.commons.lang3.time.FastDateFormat

object Utils extends Logging {

  @transient lazy val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  @transient lazy val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  @transient lazy val legacyDateFmt: FastDateFormat = FastDateFormat.getInstance("yyyy-MM-dd")
  @transient lazy val legacyDateTimeFmt: FastDateFormat = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss")

  def defaultClassLoader: ClassLoader =
    Try(Thread.currentThread.getContextClassLoader) // fail if cannot access thread context ClassLoader
      .orElse(Try(getClass.getClassLoader)) // fail indicates the bootstrap ClassLoader
      .orElse(Try(ClassLoader.getSystemClassLoader)) // fail if cannot access system ClassLoader
      .get

  def classpathResource(name: String): URI = defaultClassLoader.getResource(name).toURI

  def classpathResourceAsStream(name: String): InputStream = defaultClassLoader.getResourceAsStream(name)

  @transient lazy val tmpDirPath: Path = Files.createTempDirectory("classpath_res_")

  def copyFileFromClasspath(name: String): File = {
    val copyPath = tmpDirPath.resolve(name)
    Files.createDirectories(copyPath.getParent)
    Using.resource(classpathResourceAsStream(name)) { input =>
      Files.copy(input, copyPath, StandardCopyOption.REPLACE_EXISTING)
    }
    copyPath.toFile
  }

  def load(key: String, defValue: String = ""): String = sys.props.getOrElse(key, sys.env.getOrElse(key, defValue))

  def stripSingleQuote(maybeQuoted: String): String = {
    var start = 0
    var until = maybeQuoted.length
    if (maybeQuoted.startsWith("'")) start = 1
    if (maybeQuoted.endsWith("'") && !maybeQuoted.endsWith("\\'")) until = until - 1
    if (start > until) until = start
    maybeQuoted.substring(start, until)
  }

  def wrapBackQuote(identifier: String): String = {
    val sb = new StringBuilder(identifier.length + 2)
    if (!identifier.startsWith("`")) sb.append('`')
    sb.append(identifier)
    if (identifier == "`" || !identifier.endsWith("`") || identifier.endsWith("\\`")) sb.append('`')
    sb.mkString
  }

  @tailrec
  def retry[R, T <: Throwable: ClassTag](retryTimes: Int, interval: Duration)(f: () => R): Try[R] = {
    assert(retryTimes >= 0)
    val clazz = implicitly[ClassTag[T]].runtimeClass
    Try(f()) match {
      case Success(result) => Success(result)
      case Failure(exception) if clazz.isInstance(exception) && retryTimes > 0 =>
        log.warn(s"Execution failed cause by", exception)
        log.warn(s"$retryTimes times retry remaining, the next will be in ${interval.toMillis}ms")
        LockSupport.parkNanos(interval.toNanos)
        retry(retryTimes - 1, interval)(f)
      case Failure(exception) => Failure(exception)
    }
  }

  val PREFIX = "SPARK_ON_CLICKHOUSE"

  def setTesting(name: String = "ut"): Unit = sys.props += ((s"${PREFIX}_TESTING", name))

  def unsetTesting(): Unit = sys.props -= s"${PREFIX}_TESTING"

  def isTesting: Boolean = sys.env.contains(s"${PREFIX}_TESTING") || sys.props.contains(s"${PREFIX}_TESTING")
}
