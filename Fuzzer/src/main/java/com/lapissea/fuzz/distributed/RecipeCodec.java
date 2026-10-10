package com.lapissea.fuzz.distributed;

import com.lapissea.fuzz.FuzzingRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

public final class RecipeCodec{
	
	private static class CheckedLengthOutputStream extends OutputStream{
		private final ByteArrayOutputStream bytes;
		public CheckedLengthOutputStream(ByteArrayOutputStream bytes){ this.bytes = bytes; }
		@Override
		public void write(int value) throws IOException{
			check(1);
			bytes.write(value);
		}
		@Override
		public void write(byte[] data, int offset, int length) throws IOException{
			check(length);
			bytes.write(data, offset, length);
		}
		private void check(int length) throws IOException{
			if(length>MAX_BYTES - bytes.size()){
				throw new IOException("Recipe exceeds " + MAX_BYTES + " bytes");
			}
		}
	}
	
	public static final int MAX_BYTES = 1024*1024;
	
	private RecipeCodec(){ }
	
	public static <R extends FuzzingRunner<?, ?, ?>> byte[] encode(FuzzingRunner.Recipe<R> recipe){
		var bytes = new ByteArrayOutputStream();
		
		try(var out = new ObjectOutputStream(new CheckedLengthOutputStream(bytes))){
			out.writeObject(recipe);
		}catch(IOException e){
			throw new IllegalArgumentException("Distributed recipe should capture only serializable inputs (avoid capturing 'this'): " + e.getMessage(), e);
		}
		
		return bytes.toByteArray();
	}
	
	public static FuzzingRunner.Recipe<?> decode(byte[] bytes){
		if(bytes.length>MAX_BYTES){
			throw new IllegalArgumentException("Distributed recipe too large");
		}
		
		try(var in = new ObjectInputStream(new ByteArrayInputStream(bytes))){
			in.setObjectInputFilter(info -> {
				return info.depth()>64 || info.references()>100_000 || info.arrayLength()>MAX_BYTES?
				       ObjectInputFilter.Status.REJECTED : ObjectInputFilter.Status.UNDECIDED;
			});
			if(!(in.readObject() instanceof FuzzingRunner.Recipe<?> recipe)){
				throw new IOException("Expected a runner construction recipe");
			}
			return recipe;
		}catch(IOException|ClassNotFoundException e){
			throw new IllegalArgumentException("Cannot reconstruct distributed recipe", e);
		}
	}
}
