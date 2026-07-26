package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.compilation.JorthUtils;
import com.lapissea.iterableplus.Iters;
import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

public final class TempClassGen{
	
	public interface CodePart{
		void write2(CodeBlock body);
	}
	
	public sealed interface CtorType{
		record Empty(Map<String, Object> values) implements CtorType{
			public Empty(){
				this(Map.of());
			}
		}
		
		record Some(List<String> fieldNames) implements CtorType{ }
		
		record All() implements CtorType{ }
	}
	
	public record FieldGen(
		String name, Visibility visibility, boolean isFinal, Type type,
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
		List<UnsafeConsumer<ClassDefinition, MalformedJorth>> extras
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
			var cw = new ClassDefinition(cl);
			JorthUtils.writeAnnotations(cw, classGen.annotations);
			if(classGen.parent != null){
				cw.extendsType(classGen.parent);
			}
			cw.name(ClassName.dotted(classGen.name));
			
			cw.staticInit()
			  .body()
			  .call(IOInstance.Managed.class, "registerAccess", args -> {
				  args.val(ClassName.dotted(providerName(classGen.name)));
			  });
			
			for(FieldGen field : classGen.fields){
				var f = cw.field(field.type, field.name).visibility(field.visibility);
				if(field.isFinal) f.finalAcc();
				JorthUtils.writeAnnotations(f, field.annotations);
			}
			
			for(var ctor : classGen.constructors){
				switch(ctor){
					case CtorType.All ignored -> writeFieldsCtor(cw, classGen.fields);
					case CtorType.Empty empty -> {
						var body = cw.instanceInit()
						             .body()
						             .callSuper(e -> { });
						
						for(var e : empty.values.entrySet()){
							var name = e.getKey();
							if(Iters.from(classGen.fields).map(FieldGen::name).noneEquals(name)){
								throw new IllegalArgumentException(name + " is not a field");
							}
							var val = e.getValue();
							if(val instanceof CodePart block){
								block.write2(body);
							}else{
								body.val(val);
							}
							body.setThis(name);
						}
					}
					case CtorType.Some(var names) -> {
						List<FieldGen> list = new ArrayList<>(names.size());
						for(String name : names){
							list.add(classGen.fields.stream().filter(e -> e.name.equals(name)).findFirst().orElseThrow());
						}
						writeFieldsCtor(cw, list);
					}
				}
				for(var extra : classGen.extras){
					extra.accept(cw);
				}
			}
			
			return cw.getClassFile();
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class", e);
		}
	}
	private static void writeFieldsCtor(ClassDefinition cw, List<FieldGen> fields) throws MalformedJorth{
		var init = cw.instanceInit();
		
		for(FieldGen field : fields){
			init.arg(field.type, field.name);
		}
		var body = init.body()
		               .callSuper(e -> { });
		
		for(FieldGen field : fields){
			body.get(field.name)
			    .setThis(field.name);
		}
	}
	
	private static String providerName(String name){
		return name + "€LookupProvider";
	}
	private static byte[] makeAccessClass(ClassLoader cl, String name){
		try{
			var cw = new ClassDefinition(cl);
			cw.implement(Supplier.class).name(ClassName.dotted(name));
			cw.function("get").returns(Object.class)
			  .body()
			  .call(MethodHandles.class, "lookup");
			return cw.getClassFile();
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class", e);
		}
	}
	
}
