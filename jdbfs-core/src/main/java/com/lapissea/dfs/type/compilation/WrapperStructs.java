package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class WrapperStructs{
	
	public abstract static class Wrapper<T> extends IOInstance.Managed<Wrapper<T>>{
		public abstract T get();
	}
	
	public record WrapperRes<T>(Struct<Wrapper<T>> struct, Function<T, Wrapper<T>> constructor){ }
	
	private static final ConcurrentHashMap<Class<?>, WrapperRes<?>> WRAPPER_STRUCT_CACHE = new ConcurrentHashMap<>();
	private static final Function<Class<?>, WrapperRes<?>>          generateWrapper      = WrapperStructs::generateWrapper;
	
	public static <T> WrapperRes<T> getWrapperStruct(Class<T> type){
		if(!FieldCompiler.getWrapperTypes().contains(type)){
			return null;
		}
		var res = WRAPPER_STRUCT_CACHE.computeIfAbsent(type, generateWrapper);
		//noinspection unchecked
		return (WrapperRes<T>)res;
	}
	
	private static <T> WrapperRes<T> generateWrapper(Class<T> type){
		var name = makeName(type);
		
		try{
			var file = Jorth.generateClass(FieldCompiler.class.getClassLoader(), name, writer -> {
				writer.addImportAs(Wrapper.class, "Wrapper");
				writer.addImport(IOValue.class);
				writer.write(
					"""
						extends #Wrapper<{!0}>
						public final class {!0} start
							@ #IOValue
							private field val {1}
							
							public function <init> start
								super start end
							end
							
							public function <init>
								arg val {1}
							start
								super start end
								get #arg val
								set this val
							end
							
							public function get
								returns #Object
							start
								get this val
							end
						end
						""",
					name, type
				);
			});
			
			var lookup = MethodHandles.privateLookupIn(Wrapper.class, MethodHandles.lookup());
			//noinspection unchecked
			var wrapper = (Class<Wrapper<T>>)lookup.defineClass(file);
			var struct  = Struct.of(wrapper);
			
			var ctor = wrapper.getConstructor(type);
			
			return new WrapperRes<>(
				struct,
				Access.makeLambda(ctor, MethodHandles.privateLookupIn(wrapper, lookup), Function.class)
			);
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to write jorth for " + name, e);
		}catch(IllegalAccessException|NoSuchMethodException e){
			throw new RuntimeException("Failed to create class for " + name, e);
		}
	}
	
	private static String makeName(Class<?> type){
		int dims = 0;
		var cl   = type;
		while(cl.isArray()){
			dims++;
			cl = cl.componentType();
		}
		
		return Wrapper.class.getName() + "€" + cl.getSimpleName() + "_arr".repeat(dims);
	}
}
