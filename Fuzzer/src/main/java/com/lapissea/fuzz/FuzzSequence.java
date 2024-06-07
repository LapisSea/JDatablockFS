package com.lapissea.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

public record FuzzSequence(long startIndex, long index, long seed, int iterations) implements Serializable{
	
	public FuzzSequence{
		if(startIndex<0){
			throw new IllegalArgumentException("startIndex must be non-negative");
		}
		if(iterations<=0){
			throw new IllegalArgumentException("iterations must be positive");
		}
	}
	public long endIndex(){ return startIndex + iterations; }
	
	@Override
	public String toString(){
		int u  = 1, k = 1000, M = 1000_000;
		var us = "";
		if((startIndex%M|iterations%M) == 0){
			u = M;
			us = "M";
		}else if((startIndex%k|iterations%k) == 0){
			u = k;
			us = "k";
		}
		var start = startIndex/u;
		var end   = endIndex()/u;
		return index + "(" + start + us + "-" + end + us + ")->" + makeDataStick();
	}
	
	private static int numObytes(long size){
		if(size == 0) return 1;
		var off = size<0? -(size + 1) : size;
		return bitsToBytes(Long.SIZE - Long.numberOfLeadingZeros(off) + 1);
	}
	private static int bitsToBytes(int bits){
		return Math.ceilDiv(bits, Byte.SIZE);
	}
	private static void write(OutputStream dest, long val, int bytes) throws IOException{
		var bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		bb.putLong(val).limit(bytes);
		dest.write(bb.array(), 0, bytes);
	}
	private static long read(InputStream src, int bytes) throws IOException{
		var bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		bb.put(src.readNBytes(bytes)).position(0);
		return bb.getLong();
	}
	
	private String makeDataStick(){
		try{
			var startIndexSize = numObytes(startIndex());
			var indexSize      = numObytes(index());
			var seedSize       = numObytes(seed());
			var iterationsSize = numObytes(iterations());
			
			var buf = new ByteArrayOutputStream(2 + 8*3 + 4);
			buf.write(startIndexSize|(indexSize<<4));
			buf.write(seedSize|(iterationsSize<<4));
			
			write(buf, startIndex, startIndexSize);
			write(buf, index, indexSize);
			write(buf, seed, seedSize);
			write(buf, iterations, iterationsSize);
			
			return Base64.getEncoder().encodeToString(buf.toByteArray()).replace('/', '_').replace('+', '-');
		}catch(IOException e){
			throw new UncheckedIOException(e);
		}
	}
	
	public static FuzzSequence fromDataStick(String stick){
		try{
			var data           = Base64.getDecoder().decode(stick.replace('_', '/').replace('-', '+'));
			var startIndexSize = data[0]&0b00001111;
			var indexSize      = (data[0]&0b11110000)>>4;
			var seedSize       = data[1]&0b00001111;
			var iterationsSize = (data[1]&0b11110000)>>4;
			
			var in         = new ByteArrayInputStream(data, 2, data.length - 2);
			var startIndex = read(in, startIndexSize);
			var index      = read(in, indexSize);
			var seed       = read(in, seedSize);
			var iterations = read(in, iterationsSize);
			
			if(in.read() != -1) throw new IllegalArgumentException("Too much data in stick");
			return new FuzzSequence(startIndex, index, seed, Math.toIntExact(iterations));
		}catch(Throwable e){
			throw new IllegalArgumentException("Invalid data stick: " + stick, e);
		}
	}
	
	public FuzzSequence withIterations(int iterations){
		return new FuzzSequence(startIndex, index, seed, iterations);
	}
}
