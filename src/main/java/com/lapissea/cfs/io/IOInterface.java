package com.lapissea.cfs.io;

import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.Objects;

public interface IOInterface extends Sizable.Mod, RandomIO.Creator{
	
	@Override
	default void setSize(long requestedSize) throws IOException{
		try(var io=io()){
			io.setSize(requestedSize);
		}
	}
	
	@Override
	default long getSize() throws IOException{
		try(var io=io()){
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
		try(var io=io()){
			io.setCapacity(newCapacity);
		}
	}
	
	default long getCapacity() throws IOException{
		try(var io=io()){
			return io.getCapacity();
		}
	}
	
	
	@NotNull
	default ContentOutputStream write(boolean trimOnClose) throws IOException{ return write(0, trimOnClose); }
	@NotNull
	default ContentOutputStream write(long fileOffset, boolean trimOnClose) throws IOException{
		return io().setPos(fileOffset).outStream(trimOnClose);
	}
	
	default void write(boolean trimOnClose, byte[] data) throws IOException                 { write(0, trimOnClose, data); }
	default void write(long fileOffset, boolean trimOnClose, byte[] data) throws IOException{ write(fileOffset, trimOnClose, data.length, data); }
	default void write(long fileOffset, boolean trimOnClose, int length, byte[] data) throws IOException{
		try(var io=io()){
			io.setPos(fileOffset);
			io.write(data, 0, length);
			if(trimOnClose) io.trim();
		}
	}
	
	
	@NotNull
	default <T> T read(UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{ return read(0, reader); }
	@NotNull
	default <T> T read(long fileOffset, @NotNull UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{
		Objects.requireNonNull(reader);
		try(var io=read(fileOffset)){
			return Objects.requireNonNull(reader.apply(io));
		}
	}
	
	default void read(UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{ read(0, reader); }
	
	default void read(long fileOffset, @NotNull UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{
		Objects.requireNonNull(reader);
		
		try(var io=read(fileOffset)){
			reader.accept(io);
		}
	}
	
	@NotNull
	default ContentInputStream read() throws IOException{ return read(0); }
	@NotNull
	default ContentInputStream read(long fileOffset) throws IOException{
		return io().setPos(fileOffset).inStream();
	}
	
	
	default byte[] read(long fileOffset, int length) throws IOException{
		byte[] dest=new byte[length];
		read(fileOffset, dest);
		return dest;
	}
	
	default void read(long fileOffset, byte[] dest) throws IOException{ read(fileOffset, dest.length, dest); }
	default void read(long fileOffset, int length, byte[] dest) throws IOException{
		try(var io=io()){
			io.setPos(fileOffset);
			io.readFully(dest, 0, length);
		}
	}
	
	default byte[] readAll() throws IOException{
		try(var stream=io()){
			return stream.readRemaining();
		}
	}
	
	default void transferTo(IOInterface dest) throws IOException{ transferTo(dest, true); }
	default void transferTo(IOInterface dest, boolean trimOnClose) throws IOException{
		try(var in=read();
		    var out=dest.write(trimOnClose)){
			in.transferTo(out);
		}
	}
	
	String getName();
	
}
