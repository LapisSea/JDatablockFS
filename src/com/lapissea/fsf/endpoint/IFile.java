package com.lapissea.fsf.endpoint;

import com.lapissea.fsf.io.*;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public interface IFile<Identifier>{
	
	Identifier getPath();
	
	boolean rename(Identifier newId) throws IOException;
	
	boolean exists() throws IOException;
	
	long getSize() throws IOException;
	
	boolean delete() throws IOException;
	
	RandomIO randomIO(RandomIO.Mode mode) throws IOException;
	
	
	////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////
	
	
	default String readAllString() throws IOException{
		return readAllString(StandardCharsets.UTF_8);
	}
	
	default String readAllString(Charset charset) throws IOException{
		return readParse(in->{
			var reader=new InputStreamReader(in, charset);
			var sb    =new StringBuilder();
			int c;
			while((c=reader.read())!=-1){
				sb.append((char)c);
			}
			return sb.toString();
		});
	}
	
	default <T> T readParse(UnsafeFunction<ContentInputStream, T, IOException> parser) throws IOException{
		return readParse(0, parser);
	}
	
	default <T> T readParse(long offset, UnsafeFunction<ContentInputStream, T, IOException> parser) throws IOException{
		try(var in=read(offset)){
			return parser.apply(in);
		}
	}
	
	default byte[] readAll() throws IOException{
		return readAll(0);
	}
	
	default byte[] readAll(long offset) throws IOException{
		return readAll(offset, Math.toIntExact(getSize()-offset));
	}
	
	default byte[] readAll(long offset, int amount) throws IOException{
		return readAll(offset, new byte[amount]);
	}
	
	default byte[] readAll(long offset, byte[] bb) throws IOException{
		try(var is=read(offset)){
			is.readNBytes(bb, 0, bb.length);
		}
		return bb;
	}
	
	default ContentInputStream read() throws IOException{ return read(0); }
	
	default ContentInputStream read(long offset) throws IOException{
		var io=randomIO(RandomIO.Mode.READ_ONLY);
		io.setPos(offset);
		return new RandomInputStream(io);
	}
	
	////////////////////////////////////////////////////////
	
	default void writeAll(byte[] bytes) throws IOException{
		writeAll(0, bytes);
	}
	
	default void writeAll(long offset, byte[] bytes) throws IOException{
		try(var out=write(offset)){
			out.write(bytes);
		}
	}
	
	default ContentOutputStream write() throws IOException{ return write(0); }
	
	default ContentOutputStream write(long offset) throws IOException{
		var io=randomIO(RandomIO.Mode.READ_WRITE);
		io.setPos(offset);
		return new RandomOutputStream(io, true);
	}
}
