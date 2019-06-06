/*
 * Copyright (c) 2019 Mathias Doenitz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package io.bullet.borer.compat

import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

import _root_.scodec.bits.ByteVector
import io.bullet.borer.{ByteAccess, _}

object scodec {

  /**
    * [[ByteAccess]] for [[ByteVector]].
    */
  implicit final object ByteVectorByteAccess extends ByteAccess[ByteVector] {

    type Out = ByteVectorOutput

    @inline def isEmpty(bytes: ByteVector): Boolean = bytes.isEmpty

    @inline def sizeOf(bytes: ByteVector): Long = bytes.size

    @inline def fromByteArray(byteArray: Array[Byte]): ByteVector = ByteVector(byteArray)

    @inline def toByteArray(bytes: ByteVector): Array[Byte] = bytes.toArray

    @inline def inputFrom(bytes: ByteVector) = new FromByteVector(bytes)

    def copyToByteArray(bytes: ByteVector, byteArray: Array[Byte], startIndex: Int): ByteVector = {
      val len = byteArray.length - startIndex
      bytes.copyToArray(byteArray, startIndex)
      if (len < bytes.size) bytes.drop(len.toLong) else empty
    }

    def copyToByteBuffer(bytes: ByteVector, byteBuffer: ByteBuffer): ByteVector = {
      val copied = bytes.copyToBuffer(byteBuffer)
      if (copied < bytes.size) bytes.drop(copied.toLong) else empty
    }

    def concat(a: ByteVector, b: ByteVector) =
      if (a.nonEmpty) {
        if (b.nonEmpty) {
          a ++ b
        } else a
      } else b

    @inline def convert[B](value: B)(implicit byteAccess: ByteAccess[B]) =
      value match {
        case x: ByteVector => x
        case x             => ByteVector(byteAccess.toByteArray(x))
      }

    @inline def empty = ByteVector.empty
  }

  /**
    * Encoding and Decoding for [[ByteVector]].
    */
  implicit val ByteVectorCodec = Codec[ByteVector](_ writeBytes _, _.readBytes())

  /**
    * [[Input]] around [[ByteVector]].
    */
  implicit final object ByteVectorWrapper extends Input.Wrapper[ByteVector] {
    type In = FromByteVector
    def apply(value: ByteVector) = new FromByteVector(value)
  }

  final class FromByteVector(byteVector: ByteVector) extends Input {
    type Bytes    = ByteVector
    type Position = Input.Position

    protected var _cursor: Long = _

    @inline def cursor: Long = _cursor
    @inline def byteAccess   = ByteVectorByteAccess

    @inline def resetTo(cursor: Long) = {
      _cursor = cursor
      this
    }

    @inline def moveCursor(offset: Int): this.type = {
      _cursor += offset
      this
    }

    @inline def releaseBeforeCursor(): this.type = this

    def position(cursor: Long): Position = Input.Position(this, cursor)

    @inline def prepareRead(length: Long): Boolean = _cursor + length <= byteVector.length

    def readByte(): Byte = {
      val c = _cursor
      _cursor = c + 1
      byteVector(c)
    }

    @inline def readByteOrFF(): Byte = {
      def readPadded(): Byte = {
        _cursor += 1
        -1
      }
      if (_cursor < byteVector.length) readByte() else readPadded()
    }

    def readDoubleByteBigEndian(): Char = {
      val c = _cursor
      _cursor = c + 2
      ((byteVector(c) << 8) | byteVector(c + 1) & 0xFF).toChar
    }

    @inline def readDoubleByteBigEndianPaddedFF(): Char = {
      val remaining = byteVector.length - _cursor
      if (remaining >= 2) readDoubleByteBigEndian()
      else readDoubleByteBigEndianPaddedFF(remaining.toInt)
    }

    def readQuadByteBigEndian(): Int = {
      val c = _cursor
      _cursor = c + 4
      byteVector(c) << 24 |
      (byteVector(c + 1) & 0xFF) << 16 |
      (byteVector(c + 2) & 0xFF) << 8 |
      byteVector(c + 3) & 0xFF
    }

    @inline def readQuadByteBigEndianPaddedFF(): Int = {
      val remaining = byteVector.length - _cursor
      if (remaining >= 4) readQuadByteBigEndian()
      else readQuadByteBigEndianPaddedFF(remaining.toInt)
    }

    def readOctaByteBigEndian(): Long = {
      val c = _cursor
      _cursor = c + 8
      byteVector(c).toLong << 56 |
      (byteVector(c + 1) & 0XFFL) << 48 |
      (byteVector(c + 2) & 0XFFL) << 40 |
      (byteVector(c + 3) & 0XFFL) << 32 |
      (byteVector(c + 4) & 0XFFL) << 24 |
      (byteVector(c + 5) & 0XFFL) << 16 |
      (byteVector(c + 6) & 0XFFL) << 8 |
      byteVector(c + 7) & 0XFFL
    }

    @inline def readOctaByteBigEndianPaddedFF(): Long = {
      val remaining = byteVector.length - _cursor
      if (remaining >= 8) readOctaByteBigEndian()
      else readOctaByteBigEndianPaddedFF(remaining.toInt)
    }

    @inline def readBytes(length: Long): Bytes =
      if (length > 0) {
        val end = _cursor + length
        if (end >= 0) {
          val c = _cursor
          _cursor = end
          byteVector.slice(c, end)
        } else throw new Borer.Error.Overflow(position(cursor), "ByteVector input is limited to size 2GB")
      } else ByteVector.empty

    @inline def precedingBytesAsAsciiString(length: Int): String = {
      val slice = byteVector.slice(_cursor - length, _cursor)
      StandardCharsets.ISO_8859_1.decode(slice.toByteBuffer).toString
    }
  }

  implicit object ByteVectorOutputProvider extends Output.ToTypeProvider[ByteVector] {
    type Out = ByteVectorOutput
    def apply(bufferSize: Int) = new ByteVectorOutput(bufferSize)
  }

  /**
    * Mutable [[Output]] implementation for serializing to [[ByteVector]].
    */
  final class ByteVectorOutput(bufferSize: Int) extends Output {
    // The scodec ByteVector doesn't appear to come with an efficient builder for it,
    // so rather than wrapping each incoming Byte in an extra ByteVector instance we simply
    // write into a ByteArray and only construct a ByteVector instance at the very end
    private[this] val delegate = new Output.ToByteArray(bufferSize)

    type Self   = ByteVectorOutput
    type Result = ByteVector

    def writeByte(byte: Byte)                          = ret(delegate.writeByte(byte))
    def writeBytes(a: Byte, b: Byte)                   = ret(delegate.writeBytes(a, b))
    def writeBytes(a: Byte, b: Byte, c: Byte)          = ret(delegate.writeBytes(a, b, c))
    def writeBytes(a: Byte, b: Byte, c: Byte, d: Byte) = ret(delegate.writeBytes(a, b, c, d))
    def writeBytes[Bytes: ByteAccess](bytes: Bytes)    = ret(delegate.writeBytes(bytes))

    def result() = ByteVector(delegate.result())

    private def ret(x: delegate.Self): Self = this
  }
}
