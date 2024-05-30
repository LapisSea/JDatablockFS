package com.lapissea.jorth;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class BytecodeUtils{
	
	public static String classToString(byte[] in){
		var cr  = new ClassReader(in);
		var res = new StringWriter();
		cr.accept(new TraceClassVisitor(new PrintWriter(res)), 0);
		return res.toString();
	}
	
	public static void printClass(byte[] in){
		var cr = new ClassReader(in);
		cr.accept(new TraceClassVisitor(new PrintWriter(System.out)), 0);
	}
}
