package com.lapissea.jorth;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.jorth.redo.AccessSet;
import com.lapissea.jorth.redo.ClassDefinition;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

public final class TestUtils{
	
	static Class<?> generateAndLoadInstanceSimple(
		String className,
		UnsafeConsumer<CodeStream, MalformedJorth> generator,
		UnsafeConsumer<ClassDefinition, MalformedJorth> generator2
	) throws ReflectiveOperationException{
		return generateAndLoadInstance(className, writer -> {
			writer.write(
				"""
					public class {!} start
					""",
				className
			);
			
			generator.accept(writer);
			writer.wEnd();
		}, cw -> {
			cw.start(ClassName.dotted(className), ClassType.CLASS, AccessSet.DEFAULT, Visibility.PUBLIC);
			generator2.accept(cw);
		});
	}
	
	static Class<?> generateAndLoadInstance(
		String className,
		UnsafeConsumer<CodeStream, MalformedJorth> generator,
		UnsafeConsumer<ClassDefinition, MalformedJorth> generator2
	) throws ReflectiveOperationException{
		
		ClassDefinition cw;
		
		StringJoiner tokenStr = new StringJoiner(" ");
		var          jorth    = new Jorth(null, tokenStr::add);
		try{
			try(var writer = jorth.writer()){
				generator.accept(writer);
			}finally{
				LogUtil.println(tokenStr.toString());
			}
			
			cw = new ClassDefinition(null);
			generator2.accept(cw);
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class " + className, e);
		}
		
		var classes = jorth.listClassFiles();
		
		byte[] cwf = null;
		try{
			cwf = cw.getClassFile();
		}catch(MalformedJorth e){
			throw new RuntimeException(e);
		}
		var cwfOld = jorth.getClassFile(className);
		if(!Arrays.equals(cwf, cwfOld)){
			List<String> originalLines = Arrays.asList(BytecodeUtils.classToString(cwfOld).split("\n"));
			List<String> revisedLines  = Arrays.asList(BytecodeUtils.classToString(cwf).split("\n"));
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
		
		var loader = new ClassLoader(TestUtils.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(classes.contains(name)){
					var byt = jorth.getClassFile(name);
					BytecodeUtils.printClass(byt);
					
					return defineClass(name, ByteBuffer.wrap(byt), null);
				}
				return super.findClass(name);
			}
		};
		
		var cls = Class.forName(className, true, loader);
		if(!cls.getName().equals(className)) throw new AssertionError(cls.getName() + " " + className);
		
		LogUtil.println("Compiled:", cls);
		LogUtil.println("========================================================================");
		LogUtil.println();
		return cls;
	}
	
}
