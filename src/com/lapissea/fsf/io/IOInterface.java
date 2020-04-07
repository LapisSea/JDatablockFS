package com.lapissea.fsf.io;

import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

public interface IOInterface{
	
	/**
	 * <p>Creates a new random read and write interface.</p>
	 * <p>Writing <b>will not</b> implicitly truncate the underlying contents when closed.</p>
	 *
	 * @return RandomIO instance
	 */
	@NotNull
	RandomIO doRandom() throws IOException;
	
	default void setSize(long requestedSize) throws IOException{
		try(var io=doRandom()){
			io.setSize(requestedSize);
		}
	}
	
	default long getSize() throws IOException{
		try(var io=doRandom()){
			return io.getSize();
		}
	}
	
	/**
	 * Tries to grows or shrinks capacity as closely as it is convenient for the underlying data. <br>
	 * <br>
	 * If growing, it is required that capacity is set to greater or equal to newCapacity.<br>
	 * If shrinking, it is not required that capacity is shrunk but is required to always be greater or equal to newCapacity.
	 */
	default void setCapacity(long newCapacity) throws IOException{
		try(var io=doRandom()){
			io.setCapacity(newCapacity);
		}
	}
	
	default long getCapacity() throws IOException{
		try(var io=doRandom()){
			return io.getCapacity();
		}
	}
	
	
	@NotNull
	default ContentOutputStream write(boolean trimOnClose) throws IOException{ return write(0, trimOnClose); }
	
	@NotNull
	default ContentOutputStream write(long fileOffset, boolean trimOnClose) throws IOException{
		return new RandomOutputStream(doRandom().setPos(fileOffset), trimOnClose);
	}
	
	default void write(boolean trimOnClose, byte[] data) throws IOException                 { write(0, trimOnClose, data); }
	
	default void write(long fileOffset, boolean trimOnClose, byte[] data) throws IOException{ write(fileOffset, trimOnClose, data.length, data); }
	
	default void write(long fileOffset, boolean trimOnClose, int length, byte[] data) throws IOException{
		try(var io=doRandom()){
			io.setPos(fileOffset);
			io.write(data, 0, length);
			if(trimOnClose) io.trim();
		}
	}
	
	
	@NotNull
	default <T> T read(UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{ return read(0, reader); }
	
	@NotNull
	default <T> T read(long fileOffset, UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{
		try(var io=read(fileOffset)){
			return reader.apply(io);
		}
	}
	
	default void read(UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{ read(0, reader); }
	
	default void read(long fileOffset, UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{
		try(var io=read(fileOffset)){
			reader.accept(io);
		}
	}
	
	@NotNull
	default ContentInputStream read() throws IOException{ return read(0); }
	
	@NotNull
	default ContentInputStream read(long fileOffset) throws IOException{
		return new RandomInputStream(doRandom().setPos(fileOffset));
	}
	
	
	default byte[] read(long fileOffset, int length) throws IOException{
		byte[] dest=new byte[length];
		read(fileOffset, dest);
		return dest;
	}
	
	default void read(long fileOffset, byte[] dest) throws IOException{
		read(fileOffset, dest.length, dest);
	}
	
	default void read(long fileOffset, int length, byte[] dest) throws IOException{
		try(var io=doRandom()){
			io.setPos(fileOffset);
			io.readFully(dest, 0, length);
		}
	}
	
	default byte[] readAll() throws IOException{
		try(var stream=read()){
			byte[] data=new byte[Math.toIntExact(getSize())];
			int    read=stream.readNBytes(data, 0, data.length);
			if(DEBUG_VALIDATION){
				//noinspection AutoBoxing
				Assert(read==data.length, "Failed to read data amount specified by getSize() =", data.length, "read =", read);
			}
			return data;
		}
	}
	
	default void transferTo(IOInterface dest) throws IOException{
		transferTo(dest, true);
	}
	
	default void transferTo(IOInterface dest, boolean trimOnClose) throws IOException{
		try(var in=read();
		    var out=dest.write(trimOnClose)){
			in.transferTo(out);
		}
	}
	
	default <T extends FileObject> T readAsObject(Supplier<T> newObject) throws IOException{ return readAsObject(0, newObject); }
	
	default <T extends FileObject> T readAsObject(long offset, Supplier<T> newObject) throws IOException{
		return readAsObject(newObject.get());
	}
	
	default <T extends FileObject> T readAsObject(T object) throws IOException{ return readAsObject(0, object); }
	
	default <T extends FileObject> T readAsObject(long offset, T object) throws IOException{
		Objects.requireNonNull(object);
		try(var in=read(offset)){
			object.read(in);
		}
		return object;
	}
	
	default <T extends FileObject> T writeAsObject(T object) throws IOException                     { return writeAsObject(0, object, true); }
	
	default <T extends FileObject> T writeAsObject(T object, boolean trimOnClose) throws IOException{ return writeAsObject(0, object, trimOnClose); }
	
	default <T extends FileObject> T writeAsObject(long offset, T object, boolean trimOnClose) throws IOException{
		Objects.requireNonNull(object);
		try(var in=write(offset, trimOnClose)){
			object.write(in);
		}
		return object;
	}
	
	String getName();
}
