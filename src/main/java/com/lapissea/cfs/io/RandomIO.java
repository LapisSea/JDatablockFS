package com.lapissea.cfs.io;

import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.streams.RandomInputStream;
import com.lapissea.cfs.io.streams.RandomOutputStream;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * This interface represents a session where data can be read and optionally modified. Once
 * an instance with this interface is created, it should always be closed as the underlying
 * providers may be locking resources.
 */
@SuppressWarnings("unused")
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
			return ioMap(io->HexDump.hexDump(io, title, maxWidth));
		}
		
		/**
		 * Reads all bytes from start to end in to a byte[]. This method may fail if the data size is larger than {@link Integer#MAX_VALUE}.
		 */
		default byte[] readAll() throws IOException{
			try(var io=io()){
				byte[] data=new byte[Math.toIntExact(io.getSize())];
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
			
			try(var io=ioAt(offset)){
				return session.apply(io);
			}
		}
		/**
		 * Provides a new instance of {@link RandomIO} where its initial position will be at the specified offset.
		 */
		default RandomIO ioAt(long offset) throws IOException{
			return io().setPos(offset);
		}
		
		/**
		 * Provides an easy way to parse something. If a function takes {@link RandomIO}, reads and parses the data in to an object, it can be used here.
		 */
		default <T> T ioMap(UnsafeFunction<RandomIO, T, IOException> session) throws IOException{
			Objects.requireNonNull(session);
			
			try(var io=io()){
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
			
			try(var io=ioAt(offset)){
				session.accept(io);
			}
		}
		
		/**
		 * Provides a less user error-prone way to access a {@link RandomIO} instance. No way to forget to close it!
		 */
		default void io(UnsafeConsumer<RandomIO, IOException> session) throws IOException{
			Objects.requireNonNull(session);
			
			try(var io=io()){
				session.accept(io);
			}
		}
		
		/**
		 * Provides a new instance of {@link RandomIO}. It is open on creation and can be used immediately. Once operations with it are done, it must be closed!
		 */
		RandomIO io() throws IOException;
		
		default void write(boolean trimOnClose, ByteBuffer data) throws IOException         {write(0, trimOnClose, data);}
		
		default void write(boolean trimOnClose, byte[] data) throws IOException             {write(0, trimOnClose, data);}
		
		default void write(long offset, boolean trimOnClose, byte[] data) throws IOException{write(offset, trimOnClose, data.length, data);}
		
		default void write(long offset, boolean trimOnClose, int length, byte[] data) throws IOException{
			Objects.requireNonNull(data);
			
			try(var io=ioAt(offset)){
				io.write(data, 0, length);
				if(trimOnClose) io.trim();
			}
		}
		
		default void write(long offset, boolean trimOnClose, ByteBuffer data) throws IOException{
			Objects.requireNonNull(data);
			
			try(var io=ioAt(offset)){
				io.write(data);
				if(trimOnClose) io.trim();
			}
		}
		
		/**
		 * Provides an {@link OutputStream} that can be used as a specialised write only version of {@link RandomIO}.
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
		default ContentOutputStream write(boolean trimOnClose) throws IOException{return write(0, trimOnClose);}
		
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
		
		
		@NotNull
		default <T> T read(UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{return read(0, reader);}
		
		@NotNull
		default <T> T read(long offset, @NotNull UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{
			Objects.requireNonNull(reader);
			
			try(var io=read(offset)){
				return Objects.requireNonNull(reader.apply(io));
			}
		}
		
		default void read(UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{read(0, reader);}
		
		default void read(long offset, @NotNull UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{
			Objects.requireNonNull(reader);
			
			try(var io=read(offset)){
				reader.accept(io);
			}
		}
		
		@NotNull
		default ContentInputStream read() throws IOException{return read(0);}
		
		@NotNull
		default ContentInputStream read(long offset) throws IOException{
			return ioAt(offset).inStream();
		}
		
		
		default byte[] read(long offset, int length) throws IOException{
			byte[] dest=new byte[length];
			read(offset, dest);
			return dest;
		}
		
		default void read(long offset, byte[] dest) throws IOException{read(offset, dest.length, dest);}
		
		default void read(long offset, int length, byte[] dest) throws IOException{
			if(length==0) return;
			try(var io=ioAt(offset)){
				io.readFully(dest, 0, length);
			}
		}
		
		default void transferTo(IOInterface dest, boolean trimOnClose) throws IOException{
			try(var in=read();
			    var out=dest.write(trimOnClose)){
				in.transferTo((OutputStream)out);
			}
		}
		default void transferTo(ContentWriter dest) throws IOException{
			try(var in=read()){
				in.transferTo(dest);
			}
		}
	}
	
	
	default boolean isEmpty() throws IOException{
		return getSize()==0;
	}
	
	void setSize(long requestedSize) throws IOException;
	long getSize() throws IOException;
	
	long getPos() throws IOException;
	RandomIO setPos(long pos) throws IOException;
	
	default RandomIO setPos(INumber pos) throws IOException{
		return setPos(pos.getValue());
	}
	
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
		var pos =getPos();
		var size=getSize();
		if(pos>=size) return;
		setCapacity(pos);
	}
	
	@Override
	default long skip(long n) throws IOException{
		if(n==0) return 0;
		long toSkip=Math.min(n, remaining());
		setPos(getPos()+toSkip);
		return toSkip;
	}
	
	default long remaining() throws IOException{
		long siz=getSize();
		long pos=getPos();
		return siz-pos;
	}
	
	////////
	
	
	@Override
	int read() throws IOException;
	
	@Override
	default int read(byte[] b, int off, int len) throws IOException{
		Objects.checkFromIndexSize(off, len, b.length);
		
		int i=off;
		for(int j=off+len;i<j;i++){
			var bi=read();
			if(bi<0) break;
			b[i]=(byte)bi;
		}
		return i-off;
	}
	
	
	////////
	
	
	@Override
	void write(int b) throws IOException;
	
	@Override
	default void write(byte[] b, int off, int len) throws IOException{
		Objects.checkFromIndexSize(off, len, b.length);
		
		for(int i=off, j=off+len;i<j;i++){
			write(b[i]);
		}
	}
	
	/**
	 * Simiar to the write methods except it writes some number of 0 bytes but does not modify things such as the size of the data. (useful for clearing garbage data after some data has ben shrunk)
	 */
	void fillZero(long requestedMemory) throws IOException;
	
	default RandomIO readOnly(){
		if(isReadOnly()){
			return this;
		}
		return new RandomIOReadOnly(this);
	}
	
	boolean isReadOnly();
	
	@Override
	default ContentInputStream inStream(){
		return new RandomInputStream(this);
	}
	
	@Override
	default ContentOutputStream outStream(){return outStream(true);}
	default ContentOutputStream outStream(boolean trimOnClose){return new RandomOutputStream(this, trimOnClose);}
	
	default ChunkPointer posAsPtr() throws IOException{
		return ChunkPointer.of(getPos());
	}
	
	@Override
	default long transferTo(ContentWriter out) throws IOException{
		int buffSize=8192;
		
		var remaining=remaining();
		if(remaining<buffSize){
			buffSize=Math.max((int)remaining, 8);
		}
		
		return transferTo(out, buffSize);
	}
}
