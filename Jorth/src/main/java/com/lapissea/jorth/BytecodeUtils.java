package com.lapissea.jorth;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.lapissea.util.ConsoleColors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
	public static void compareClasses(byte[] cwf, byte[] cwfOld){
		if(!Arrays.equals(cwf, cwfOld)){
			reportClassMissmatch(cwf, cwfOld);
		}
	}
	private static void reportClassMissmatch(byte[] cwf, byte[] cwfOld){
		List<String> originalLines = Arrays.asList(classToString(cwfOld).split("\n"));
		List<String> revisedLines  = Arrays.asList(classToString(cwf).split("\n"));
		var diff = UnifiedDiffUtils.generateUnifiedDiff(
			"Original bytecode",
			"New bytecode",
			originalLines,
			DiffUtils.diff(originalLines, revisedLines),
			Integer.MAX_VALUE/2
		);
		
		var str = diff.stream().map(line -> {
			
			if(line.startsWith("+") && !line.startsWith("+++")){
				return (ConsoleColors.GREEN + line + ConsoleColors.RESET);
			}else if(line.startsWith("-") && !line.startsWith("---")){
				return (ConsoleColors.RED + line + ConsoleColors.RESET);
			}else if(line.startsWith("@@") || line.startsWith("---") || line.startsWith("+++")){
				return (ConsoleColors.CYAN + line + ConsoleColors.RESET);
			}else{
				return (line);
			}
		}).collect(Collectors.joining("\n"));
		System.out.println(str);
		throw new AssertionError("Class files not equal");
	}
}
