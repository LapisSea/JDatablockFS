package com.lapissea.dfs.run;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.compilation.JorthLogger;
import com.lapissea.dfs.type.compilation.JorthUtils;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
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
	
	public enum VisiblityGen{
		PRIVATE,
		PROTECTED,
		PUBLIC
	}
	
	public record FieldGen(
		String name, VisiblityGen visibility, boolean isFinal, Type type,
		Iterable<Annotation> annotations, Function<RandomGenerator, Object> generator
	){
		@Override
		public String toString(){
			return Iters.from(annotations).joinAsOptionalStr("\n", "", "\n", a -> "@" + a.annotationType().getSimpleName()).orElse("") +
			       visibility.toString().toLowerCase() + " " + (isFinal? "final " : "") + type.getTypeName() + " " + name + ";";
		}
		@Override
		public boolean equals(Object o){
			return this == o ||
			       o instanceof FieldGen that &&
			       this.isFinal == that.isFinal &&
			       this.visibility == that.visibility &&
			       this.type.equals(that.type) &&
			       this.name.equals(that.name) &&
			       this.annotations.equals(that.annotations) &&
			       (this.generator == null) == (that.generator == null);
		}
		@Override
		public int hashCode(){
			int result = name.hashCode();
			result = 31*result + type.hashCode();
			result = 31*result + annotations.hashCode();
			result = 31*result + Boolean.hashCode(isFinal);
			return result;
		}
	}
	
	public record ClassGen(
		String name,
		List<FieldGen> fields,
		Set<CtorType> constructors,
		Class<?> parent,
		List<Annotation> annotations,
		List<UnsafeConsumer<CodeStream, MalformedJorth>> extras
	){
		public ClassGen{
			Objects.requireNonNull(name);
			Objects.requireNonNull(fields);
			Objects.requireNonNull(constructors);
			Objects.requireNonNull(annotations);
			Objects.requireNonNull(extras);
		}
		
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
		public ClassGen withName(String name){
			return new ClassGen(name, fields, constructors, parent, annotations, List.of());
		}
	}
	
	public static Class<IOInstance<?>> gen(ClassGen classGen){
		try{
			var cl = new ClassLoader(TempClassGen.class.getClassLoader()){
				static{ ClassLoader.registerAsParallelCapable(); }
				
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException{
					var baseName = classGen.name;
					if(name.equals(baseName)){
						var bytecode = makeClass(this, classGen);
						return defineClass(name, bytecode, 0, bytecode.length);
					}
					if(name.equals(providerName(baseName))){
						var bytecode = makeAccessClass(this, providerName(baseName));
						return defineClass(name, bytecode, 0, bytecode.length);
					}
					throw new ClassNotFoundException(name);
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
			var log = JorthLogger.make();
			var clazzBytes = Jorth.generateClass(cl, classGen.name, code -> {
				JorthUtils.writeAnnotations(code, classGen.annotations);
				if(classGen.parent != null){
					code.write("extends {}", classGen.parent);
				}
				code.write("public class {!} start", classGen.name);
				code.write(
					"""
						function <clinit> start
							static call {} registerAccess start
								class {}
							end
						end
						""", IOInstance.Managed.class, providerName(classGen.name));
				
				for(FieldGen field : classGen.fields){
					JorthUtils.writeAnnotations(code, field.annotations);
					if(field.isFinal) code.write("final");
					code.write("{} field {!} {}", field.visibility, field.name, field.type);
				}
				
				for(var ctor : classGen.constructors){
					switch(ctor){
						case CtorType.All ignored -> {
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
					for(var extra : classGen.extras){
						extra.accept(code);
					}
				}
				
				code.wEnd();
			}, log);
			if(log != null){
				Log.log("Jorth code for TempClassGen:\n" + log.output());
			}
			return clazzBytes;
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class", e);
		}
	}
	private static String providerName(String name){
		return name + "€LookupProvider";
	}
	private static byte[] makeAccessClass(ClassLoader cl, String name){
		try{
			return Jorth.generateClass(cl, name, code -> {
				code.addImports(Supplier.class, MethodHandles.class);
				code.write(
					"""
						implements #Supplier
						public class {!} start
							public function get
								returns #Object
							start
								static call #MethodHandles lookup
							end
						end
						""", name);
			});
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class", e);
		}
	}
	
}
