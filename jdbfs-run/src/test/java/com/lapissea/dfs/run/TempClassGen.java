package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.compilation.JorthUtils;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;

public final class TempClassGen{
	
	public interface CodePart{
		void write(CodeStream dest);
	}
	
	public sealed interface CtorType{
		record Empty(Map<String, Object> values) implements CtorType{
			public Empty(){
				this(Map.of());
			}
		}
		
		record All() implements CtorType{ }
	}
	
	public record FieldGen(String name, Type type, Iterable<Annotation> annotations, boolean isFinal, Function<RandomGenerator, Object> generator){
		@Override
		public String toString(){
			return Iters.from(annotations).joinAsOptionalStr("\n", "", "\n", a -> "@" + a.annotationType().getSimpleName()).orElse("") +
			       "public " + (isFinal? "final " : "") + type.getTypeName() + " " + name + ";";
		}
	}
	
	public record ClassGen(String name, List<FieldGen> fields, Set<CtorType> constructors, Class<?> parent){
		@Override
		public String toString(){
			return "class " + name + " " + (parent != null? "extends " + parent.getSimpleName() + " " : "") + "{" +
			       Iters.concat(
				            Iters.from(fields),
				            List.of(""),
				            Iters.from(constructors)
			            )
			            .flatMapArray(e -> e.toString().split("\n"))
			            .map(String::trim)
			            .joinAsStr("\n", l -> "\t" + l) +
			       "\n}";
		}
	}
	
	public static Class<IOInstance<?>> gen(ClassGen classGen){
		try{
			var cl = new ClassLoader(){
				static{ ClassLoader.registerAsParallelCapable(); }
				
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException{
					if(!classGen.name.equals(name)) throw new ClassNotFoundException(name);
					var bytecode = makeClass(this, classGen);
					return defineClass(name, bytecode, 0, bytecode.length);
				}
			};
			//noinspection unchecked
			return (Class<IOInstance<?>>)cl.loadClass(classGen.name);
		}catch(ClassNotFoundException e){
			throw new RuntimeException(e);
		}
	}
	private static byte[] makeClass(ClassLoader cl, ClassGen classGen){
		try{
			return Jorth.generateClass(cl, classGen.name, code -> {
				if(classGen.parent != null){
					code.write("extends {}", classGen.parent);
				}
				code.write("public class {!} start", classGen.name);
				
				for(FieldGen field : classGen.fields){
					JorthUtils.writeAnnotations(code, field.annotations);
					if(field.isFinal) code.write("final");
					code.write("public field {!} {}", field.name, field.type);
				}
				
				for(var ctor : classGen.constructors){
					switch(ctor){
						case CtorType.All all -> {
							code.write(
								"""
									public function <init>
										template-for #field in {0} start
											arg #field.name #field.type
										end
									start
										super start end
										template-for #field in {0} start
											get #arg #field.name
											set this #field.name
										end
									end
									""", classGen.fields);
						}
						case CtorType.Empty empty -> {
							code.write(
								"""
									public function <init> start
										super start end
									""");
							for(var e : empty.values.entrySet()){
								var name = e.getKey();
								if(Iters.from(classGen.fields).map(FieldGen::name).noneEquals(name)){
									throw new IllegalArgumentException(name + " is not a field");
								}
								var val = e.getValue();
								if(val instanceof CodePart block){
									block.write(code);
								}else{
									code.write("{}", val);
								}
								code.write("set this {!}", name);
							}
							code.wEnd();
						}
					}
				}
				
				code.wEnd();
			});
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class", e);
		}
	}
	
}
