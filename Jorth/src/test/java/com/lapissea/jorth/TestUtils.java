package com.lapissea.jorth;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.redo.ClassDefinition;
import com.lapissea.jorth.redo.CodeBlock;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

public final class TestUtils{
	
	public static String autoName(){
		return "test.CL_" + StackWalker.getInstance().walk(
			s -> s.skip(1)
			      .findFirst()
			      .map(StackWalker.StackFrame::getMethodName)
			      .orElse("Unknown")
		);
	}
	
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
			cw.name(ClassName.dotted(className));
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
			cw = new ClassDefinition(null);
			generator2.accept(cw);
			
			try(var writer = jorth.writer()){
				generator.accept(writer);
			}finally{
				LogUtil.println(tokenStr.toString());
			}
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class " + className, e);
		}
		
		var classes = jorth.listClassFiles();
		
		byte[] cwf;
		try{
			cwf = cw.getClassFile();
		}catch(MalformedJorth e){
			throw new RuntimeException(e);
		}
		var cwfOld = jorth.getClassFile(className);
		compareClasses(cwf, cwfOld);
		
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
	
	static Class<?> generateAndLoadInstanceMulti(
		String className,
		UnsafeConsumer<CodeStream, MalformedJorth> generator,
		UnsafeBiConsumer<String, ClassDefinition, MalformedJorth> generator2
	) throws ReflectiveOperationException{
		
		
		StringJoiner tokenStr = new StringJoiner(" ");
		var          jorth    = new Jorth(null, tokenStr::add);
		try{
			try(var writer = jorth.writer()){
				generator.accept(writer);
			}finally{
				LogUtil.println(tokenStr.toString());
			}
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class " + className, e);
		}
		
		var classes = jorth.listClassFiles();
		
		var loader = new ClassLoader(TestUtils.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(classes.contains(name)){
					
					byte[] cwf;
					try{
						ClassDefinition cw;
						cw = new ClassDefinition(this);
						generator2.accept(name, cw);
						cwf = cw.getClassFile();
					}catch(Throwable e){
						e.printStackTrace();
						throw new RuntimeException(e);
					}
					
					var byt = jorth.getClassFile(name);
					compareClasses(cwf, byt);
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
	
	public static void compareClasses(byte[] cwf, byte[] cwfOld){
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
	}
	
	public record TArg(String name, Class<?> type){ }
	
	public record InterfaceTemplate<T>(Class<T> type, String functionName, List<TArg> args, Type returns){ }
	
	public static final InterfaceTemplate<IntUnaryOperator> INT_UNARY_OPERATOR = new InterfaceTemplate<>(
		IntUnaryOperator.class, "applyAsInt", List.of(new TArg("num", int.class)), int.class
	);
	
	public static <T> T generateInterface(
		String name,
		InterfaceTemplate<T> template,
		UnsafeConsumer<CodeStream, MalformedJorth> generator,
		UnsafeConsumer<CodeBlock, MalformedJorth> generator2
	) throws ReflectiveOperationException{
		var cls = generateAndLoadInstance(name, writer -> {
			writer.write(
				"""
					implements {}
					class {} start
						@ #Override
						public function {}
						{}
						{}
						start
					""",
				template.type,
				name,
				template.functionName,
				template.args.stream().map(e -> "arg " + e.name() + " " + JorthUtils.toJorthGeneric(e.type())).collect(Collectors.joining("\n")),
				(template.returns != null? "returns " + JorthUtils.toJorthGeneric(template.returns) : "")
			);
			
			generator.accept(writer);
			writer.write(
				"""
						end
					end
					"""
			);
		}, cd -> {
			cd.name(ClassName.dotted(name)).implement(template.type);
			var fn = cd.function(template.functionName);
			for(var arg : template.args){
				fn.arg(arg.type(), arg.name());
			}
			if(template.returns != null){
				fn.returns(template.returns);
			}
			fn.annotation(Override.class);
			generator2.accept(fn.body());
		});
		
		Object instO = cls.getConstructor().newInstance();
		return template.type.cast(instO);
	}
}
