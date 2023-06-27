package com.lapissea.jorth;

import com.lapissea.util.ByteBufferBackedInputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

public class BytecodeUtils{
	
	public static void printClass(ByteBuffer in){
		try{
			printClass(new ByteBufferBackedInputStream(in.asReadOnlyBuffer()));
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	public static void printClass(byte[] in){
		ClassReader cr = new ClassReader(in);
		cr.accept(new TraceClassVisitor(new PrintWriter(System.out)), 0);
	}
	
	public static void printClass(InputStream in) throws Exception{
		ClassReader cr = new ClassReader(in);
		cr.accept(new TraceClassVisitor(new PrintWriter(System.out)), 0);
	}
}
