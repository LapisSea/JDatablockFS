package com.lapissea.jorth;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.lapissea.util.ConsoleColors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class BytecodeUtils{
	private static Path JAVAP_TEMP_FILE;
	
	private static synchronized String javapDump(byte[] classBytes){
		Path tmpFile = JAVAP_TEMP_FILE;
		if(tmpFile == null){
			try{
				var dir = Files.createTempDirectory("classdiff");
				tmpFile = dir.resolve("Dump.class");
			}catch(Exception e){
				tmpFile = Path.of(System.getProperty("java.io.tmpdir"), "classdiff-dump.class");
			}
			JAVAP_TEMP_FILE = tmpFile;
		}
		
		try{
			Files.write(tmpFile, classBytes);
			
			var proc = new ProcessBuilder("javap", "-v", "-p", "-c", "-s", "-constants", tmpFile.toString())
				           .redirectErrorStream(true).start();
			String out;
			try(var in = proc.getInputStream()){
				out = new String(in.readAllBytes());
			}
			proc.waitFor();
			return out;
		}catch(Exception e){
			var sw = new StringWriter();
			e.printStackTrace(new PrintWriter(sw));
			return "javapDump failed: " + sw;
		}
	}
	
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
			// This is a trick to sort constant pool so that differences that change nothing don't get reported as an error
			var canonNew = canonicalize(cwf);
			var canonOld = canonicalize(cwfOld);
			
			if(!Arrays.equals(canonNew, canonOld)){
				reportClassMissmatch(canonNew, canonOld);
			}
		}
	}
	private static byte[] canonicalize(byte[] classBytes){
		var cr = new ClassReader(classBytes);
		var cw = new ClassWriter(0);
		cr.accept(cw, 0);
		return cw.toByteArray();
	}
	private static void reportClassMissmatch(byte[] classFile, byte[] classFileOld){
		List<String> originalLines = Arrays.asList(classToString(classFileOld).split("\n"));
		List<String> revisedLines  = Arrays.asList(classToString(classFile).split("\n"));
		var          diff          = makeDiff(originalLines, revisedLines);
		
		if(diff.isEmpty()){
			originalLines = Arrays.asList(javapDump(classFileOld).split("\n"));
			revisedLines = Arrays.asList(javapDump(classFile).split("\n"));
			diff = makeDiff(originalLines, revisedLines);
		}
		
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
		System.err.println(str);
		throw new AssertionError("Class files not equal");
	}
	private static List<String> makeDiff(List<String> originalLines, List<String> revisedLines){
		return UnifiedDiffUtils.generateUnifiedDiff(
			"Original bytecode",
			"New bytecode",
			originalLines,
			DiffUtils.diff(originalLines, revisedLines),
			Integer.MAX_VALUE/2
		);
	}
}
