package com.lapissea.dfs.io;

import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.streams.RandomInputStream;
import com.lapissea.dfs.io.streams.RandomOutputStream;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.utils.IOUtils;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Objects;

import static com.lapissea.dfs.config.GlobalConfig.BATCH_BYTES;

/**
 * This interface represents a session where data can be read and optionally modified. Once
 * an instance with this interface is created, it should always be closed as the underlying
 * providers may be locking resources.
 */
@SuppressWarnings({"unused", "resource"})
public interface RandomIO extends Flushable, ContentWriter, ContentReader{
	
	/**
	 * This interface signifies that a type can provide instances of {@link RandomIO}. All the
	 * default functions are related quality of life helpers that hide away the somewhat verbose
	 * nature of {@link RandomIO}.
	 */
	interface Creator{
		
		default StringBuilder hexdump() throws IOException{
			return hexdump(HexDump.DEFAULT_MAX_WIDTH);
		}
		
		default StringBuilder hexdump(int maxWidth) throws IOException{
			return hexdump(TextUtil.toNamedJson(this), maxWidth);
		}
		
		default StringBuilder hexdump(String title) throws IOException{
			return hexdump(title, HexDump.DEFAULT_MAX_WIDTH);
		}
		
		default StringBuilder hexdump(String title, int maxWidth) throws IOException{
			return ioMap(io -> HexDump.hexDump(io, title, maxWidth));
		}
		
		/**
		 * Reads all bytes from start to end in to a byte[]. This method may fail if the data size is larger than {@link Integer#MAX_VALUE}.
		 */
		default byte[] readAll() throws IOException{
			try(var io = io()){
				byte[] data = new byte[Math.toIntExact(io.getSize())];
				io.readFully(data);
				return data;
			}
		}
		
		/**
		 * Provides a less user error-prone way to access a {@link RandomIO} instance with an ability to return a result.
		 *
		 * @param ptr represents the place where the position will be placed relative to the start of the data.
		 */
		default <T> T ioMapAt(ChunkPointer ptr, UnsafeFunction<RandomIO, T, IOException> session) throws IOException{
			return ioMapAt(ptr.getValue(), session);
		}
		
		/**
		 * Provides a less user error-prone way to access a {@link RandomIO} instance with an ability to return a result.
		 *
		 * @param offset represents the place where the position will be placed relative to the start of the data.
		 */
		default <T> T ioMapAt(long offset, UnsafeFunction<RandomIO, T, IOException> session) throws IOException{
			Objects.requireNonNull(session);
			
			try(var io = ioAt(offset)){
				return session.apply(io);
			}
		}
		/**
		 * Provides a new instance of {@link RandomIO} where its initial position will be at the specified offset.
		 */
		default RandomIO ioAt(long offset) throws IOException{
			var io = io();
			io.skipExact(offset);
			return io;
		}
		
		/**
		 * Provides an easy way to parse something. If a function takes {@link RandomIO}, reads and parses the data in to an object, it can be used here.
		 */
		default <T> T ioMap(UnsafeFunction<RandomIO, T, IOException> session) throws IOException{
			Objects.requireNonNull(session);
			
			try(var io = io()){
				return session.apply(io);
			}
		}
		
		/**
		 * Provides a less user error-prone way to access a {@link RandomIO} instance.
		 */
		default void ioAt(ChunkPointer ptr, UnsafeConsumer<RandomIO, IOException> session) throws IOException{
			ioAt(ptr.getValue(), session);
		}
		
		/**
		 * Provides a less user error-prone way to access a {@link RandomIO} instance. No way to forget to close it!
		 *
		 * @param offset represents the place where the position will be placed relative to the start of the data.
		 */
		default void ioAt(long offset, UnsafeConsumer<RandomIO, IOException> session) throws IOException{
			Objects.requireNonNull(session);
			
			try(var io = ioAt(offset)){
				session.accept(io);
			}
		}
		
		/**
		 * Provides a less user error-prone way to access a {@link RandomIO} instance. No way to forget to close it!
		 */
		default void io(UnsafeConsumer<RandomIO, IOException> session) throws IOException{
			Objects.requireNonNull(session);
			
			try(var io = io()){
				session.accept(io);
			}
		}
		
		/**
		 * Provides a new instance of {@link RandomIO}. It is open on creation and can be used immediately. Once operations with it are done, it must be closed!
		 */
		RandomIO io() throws IOException;
		
		default RandomIO readOnlyIO() throws IOException{
			var io = io();
			if(io.isReadOnly()) return io;
			return new RandomIOReadOnly(io);
		}
		
		default void writeUTF(boolean trimOnClose, CharSequence data) throws IOException{ writeUTF(0, trimOnClose, data); }
		default void writeUTF(long offset, boolean trimOnClose, CharSequence data) throws IOException{
			Objects.requireNonNull(data);
			
			try(var io = ioAt(offset)){
				io.writeUTF(data);
				if(trimOnClose) io.trim();
			}
		}
		
		default void set(ByteBuffer data) throws IOException                                { write(0, true, data); }
		
		default void set(byte[] data) throws IOException                                    { write(0, true, data); }
		
		default void write(boolean trimOnClose, ByteBuffer data) throws IOException         { write(0, trimOnClose, data); }
		
		default void write(boolean trimOnClose, byte[] data) throws IOException             { write(0, trimOnClose, data); }
		
		default void write(long offset, boolean trimOnClose, byte[] data) throws IOException{ write(offset, trimOnClose, data.length, data); }
		
		default void write(long offset, boolean trimOnClose, int length, byte[] data) throws IOException{
			Objects.requireNonNull(data);
			
			try(var io = ioAt(offset)){
				io.write(data, 0, length);
				if(trimOnClose) io.trim();
			}
		}
		
		default void write(long offset, boolean trimOnClose, ByteBuffer data) throws IOException{
			Objects.requireNonNull(data);
			
			try(var io = ioAt(offset)){
				io.write(data);
				if(trimOnClose) io.trim();
			}
		}
		
		/**
		 * Provides an {@link OutputStream} that can be used as specialized write only version of {@link RandomIO}.
		 *
		 * @param trimOnClose signifies if data should be trunctuated / trimmed once writing is done.
		 *                    Example:<br>
		 *                    Existing/old data:<br>
		 *                    0, 1, 2, 3, 4, 5<br>
		 *                    written data:<br>
		 *                    7, 8, 9<br>
		 *                    new data if true:<br>
		 *                    7, 8, 9<br>
		 *                    new data if false:<br>
		 *                    7, 8, 9, 3, 4, 5
		 */
		@NotNull
		default ContentOutputStream write(boolean trimOnClose) throws IOException{ return write(0, trimOnClose); }
		
		/**
		 * Provides an {@link OutputStream} that can be used as a specialised write only version of {@link RandomIO}.
		 *
		 * @param offset      the initial bytes to skip from the start of the data
		 * @param trimOnClose signifies if data should be trunctuated / trimmed once writing is done.
		 *                    Example:<br>
		 *                    Existing/old data:<br>
		 *                    0, 1, 2, 3, 4, 5<br>
		 *                    written data:<br>
		 *                    7, 8, 9<br>
		 *                    new data if true:<br>
		 *                    7, 8, 9<br>
		 *                    new data if false:<br>
		 *                    7, 8, 9, 3, 4, 5
		 */
		@NotNull
		default ContentOutputStream write(long offset, boolean trimOnClose) throws IOException{
			return ioAt(offset).outStream(trimOnClose);
		}
		
		default String readUTF(long offset) throws IOException{
			try(var io = ioAt(offset)){
				return io.readUTF();
			}
		}
		
		@NotNull
		default <T> T read(UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{ return read(0, reader); }
		
		@NotNull
		default <T> T read(long offset, @NotNull UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{
			Objects.requireNonNull(reader);
			
			try(var io = read(offset)){
				return Objects.requireNonNull(reader.apply(io));
			}
		}
		
		default void read(UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{ read(0, reader); }
		
		default void read(long offset, @NotNull UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{
			Objects.requireNonNull(reader);
			
			try(var io = read(offset)){
				reader.accept(io);
			}
		}
		
		@NotNull
		default ContentInputStream read() throws IOException{ return read(0); }
		
		@NotNull
		default ContentInputStream read(long offset) throws IOException{
			return ioAt(offset).inStream();
		}
		
		
		default byte[] read(long offset, int length) throws IOException{
			byte[] dest = new byte[length];
			read(offset, dest);
			return dest;
		}
		
		default void read(long offset, byte[] dest) throws IOException{ read(offset, dest.length, dest); }
		
		default void read(long offset, int length, byte[] dest) throws IOException{
			if(length == 0) return;
			try(var io = ioAt(offset)){
				io.readFully(dest, 0, length);
			}
		}
		
		default void transferTo(IOInterface dest, boolean trimOnClose) throws IOException{
			try(var in = read();
			    var out = dest.write(trimOnClose)){
				in.transferTo((OutputStream)out);
			}
		}
		default void transferTo(ContentWriter dest) throws IOException{
			try(var in = io()){
				in.transferTo(dest);
			}
		}
		default void transferTo(OutputStream dest) throws IOException{
			try(var in = io()){
				in.transferTo(dest);
			}
		}
	}
	
	
	default boolean isEmpty() throws IOException{
		return getSize() == 0;
	}
	
	void setSize(long requestedSize) throws IOException;
	long getSize() throws IOException;
	
	long getPos() throws IOException;
	RandomIO setPos(long pos) throws IOException;
	
	long getCapacity() throws IOException;
	RandomIO setCapacity(long newCapacity) throws IOException;
	
	default RandomIO ensureCapacity(long capacity) throws IOException{
		if(getCapacity()<capacity){
			setCapacity(capacity);
		}
		return this;
	}
	
	@Override
	void close() throws IOException;
	
	@Override
	void flush() throws IOException;
	
	default void trim() throws IOException{
		var pos  = getPos();
		var size = getSize();
		if(pos>=size) return;
		setCapacity(pos);
	}
	
	@Override
	default long skip(long n) throws IOException{
		if(n == 0) return 0;
		long toSkip = Math.min(n, remaining());
		setPos(getPos() + toSkip);
		return toSkip;
	}
	
	default long remaining() throws IOException{
		long siz = getSize();
		long pos = getPos();
		return siz - pos;
	}
	
	////////
	
	
	@Override
	int read() throws IOException;
	
	@Override
	default int read(byte[] b, int off, int len) throws IOException{
		Objects.checkFromIndexSize(off, len, b.length);
		
		int i = off;
		for(int j = off + len; i<j; i++){
			var bi = read();
			if(bi<0){
				if(i == off) return -1;
				break;
			}
			b[i] = (byte)bi;
		}
		return i - off;
	}
	
	
	////////
	
	
	@Override
	void write(int b) throws IOException;
	
	@Override
	default void write(byte[] b, int off, int len) throws IOException{
		Objects.checkFromIndexSize(off, len, b.length);
		
		for(int i = off, j = off + len; i<j; i++){
			write(b[i]);
		}
	}
	
	/**
	 * Writes data from all elements. Does not change cursor position
	 */
	void writeAtOffsets(Collection<WriteChunk> data) throws IOException;
	
	/**
	 * Simiar to the write methods except it writes some number of 0 bytes but does not modify things such as the size of the data. (useful for clearing garbage data after some data has been shrunk)
	 */
	void fillZero(long requestedMemory) throws IOException;
	
	boolean isReadOnly();
	
	@Override
	default ContentInputStream inStream(){
		return new RandomInputStream(this);
	}
	
	@Override
	default ContentOutputStream outStream(){ return outStream(true); }
	default ContentOutputStream outStream(boolean trimOnClose){ return new RandomOutputStream(this, trimOnClose); }
	
	default ChunkPointer posAsPtr() throws IOException{
		return ChunkPointer.of(getPos());
	}
	
	@Override
	default long transferTo(ContentWriter out) throws IOException{
		int buffSize = BATCH_BYTES;
		
		var remaining = remaining();
		if(remaining<buffSize){
			buffSize = Math.max((int)remaining, 8);
		}
		
		return transferTo(out, buffSize);
	}
	@Override
	default long transferTo(OutputStream out) throws IOException{
		int buffSize = BATCH_BYTES;
		
		var remaining = remaining();
		if(remaining<buffSize){
			buffSize = Math.max((int)remaining, 8);
		}
		
		return transferTo(out, buffSize);
	}
	
	record WriteChunk(long ioOffset, int dataOffset, int dataLength, byte[] data) implements Comparable<WriteChunk>{
		public WriteChunk(long ioOffset, byte[] data){
			this(ioOffset, 0, data.length, data);
		}
		public WriteChunk(long ioOffset, byte[] data, int dataLength){
			this(ioOffset, 0, dataLength, data);
		}
		public WriteChunk{
			Objects.requireNonNull(data);
			if(ioOffset<0) throw new IllegalArgumentException("ioOffset (" + ioOffset + ") can't be negative");
			if(dataOffset<0) throw new IllegalArgumentException("dataOffset (" + dataOffset + ") can't be negative");
			if(dataLength<0) throw new IllegalArgumentException("dataLength (" + dataLength + ") can't be negative");
			if(dataOffset + dataLength>data.length) throw new IndexOutOfBoundsException(
				"dataOffset (" + dataOffset + ") + dataLength (" + dataLength + ") must be less or equal to data.length (" + data.length + ")");
		}
		
		public long ioEnd(){
			return ioOffset + dataLength;
		}
		
		public WriteChunk withOffset(long ioOffset){
			return new WriteChunk(ioOffset, dataOffset, dataLength, data);
		}
		
		public record Split(WriteChunk before, WriteChunk after){ }
		
		public Split split(int pos){
			if(pos == 0) throw new IllegalArgumentException();
			Objects.checkIndex(pos, data.length);
			
			var before = new WriteChunk(ioOffset, dataOffset, pos, data);
			var after  = new WriteChunk(ioOffset + pos, dataOffset + pos, dataLength - pos, data);
			
			return new Split(before, after);
		}
		
		@Override
		public int compareTo(WriteChunk o){
			var c = Long.compare(ioOffset, o.ioOffset);
			if(c == 0) c = Integer.compare(dataOffset, o.dataOffset);
			if(c == 0) c = Integer.compare(dataLength, o.dataLength);
			return c;
		}
	}
	
	default boolean inTransaction(){
		return false;
	}
	
	/**
	 * This serves as an atomic write statement. Data that is related and can not be written at once should use this.<br>
	 * The function may refuse to create a local transaction buffer if it is already inside an active transaction (local or global).
	 * If the transaction refuses, it returns the current instance.<br>
	 * The returning io should only be closed if the function did not refuse to make a buffer.
	 *
	 * @return RandomIO instance that may be the calling instance or a new buffer
	 * @throws IOException source may throw io
	 */
	default RandomIO localTransactionBuffer(boolean closeSource) throws IOException{
		if(IOTransaction.DISABLE_TRANSACTIONS || inTransaction()) return this;
		
		if(isReadOnly()){
			throw new IllegalStateException();
		}
		
		class LocalTransactionIO implements RandomIO{
			private final IOTransactionBuffer transactionBuffer = new IOTransactionBuffer(false);
			
			private       long size     = RandomIO.this.getSize();
			private final long capacity = RandomIO.this.getCapacity();
			private       long pos      = RandomIO.this.getPos();
			
			LocalTransactionIO() throws IOException{
			}
			
			@Override
			public void setSize(long requestedSize){
				size = requestedSize;
			}
			@Override
			public long getSize(){
				return size;
			}
			@Override
			public long getPos(){
				return pos;
			}
			@Override
			public RandomIO setPos(long pos){
				this.pos = pos;
				return this;
			}
			@Override
			public long getCapacity(){
				return transactionBuffer.getCapacity(capacity);
			}
			@Override
			public RandomIO setCapacity(long newCapacity){
				transactionBuffer.capacityChange(newCapacity);
				return this;
			}
			
			@Override
			public void close() throws IOException{
				transactionBuffer.export().apply(RandomIO.this);
				RandomIO.this.setPos(pos);
				if(closeSource) RandomIO.this.close();
			}
			
			@Override
			public void flush(){ }
			
			private int readAt(long offset, byte[] b, int off, int len) throws IOException{
				RandomIO.this.setPos(offset);
				return RandomIO.this.read(b, off, len);
			}
			
			@Override
			public int read() throws IOException{
				var b = transactionBuffer.readByte(this::readAt, pos);
				if(b>=0){
					pos++;
				}
				return b;
			}
			@Override
			public void write(int b){
				transactionBuffer.writeByte(pos, b);
				pos++;
				if(size<=pos) size = pos + 1;
			}
			
			@Override
			public void writeAtOffsets(Collection<WriteChunk> data){
				for(var e : data){
					transactionBuffer.write(e.ioOffset, e.data, e.dataOffset, e.dataLength);
				}
			}
			@Override
			public void fillZero(long requestedMemory) throws IOException{
				var pos = this.pos;
				IOUtils.zeroFill(this, requestedMemory);
				this.pos = pos;
			}
			
			@Override
			public boolean isReadOnly(){
				return false;
			}
			@Override
			public boolean inTransaction(){
				return true;
			}
		}
		
		return new LocalTransactionIO();
	}
}
