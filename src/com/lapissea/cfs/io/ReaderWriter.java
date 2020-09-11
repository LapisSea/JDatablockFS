package com.lapissea.cfs.io;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.util.Nullable;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

public interface ReaderWriter<T>{
	
	ReaderWriter<?> DUMMY=new ReaderWriter<>(){
		@Override
		public Object read(Object targetObj, ContentReader source, Object oldValue){
			throw new UnsupportedOperationException();
		}
		@Override
		public void write(Object targetObj, ContentWriter target, Object source){
			throw new UnsupportedOperationException();
		}
		@Override
		public long mapSize(Object targetObj, Object source){
			throw new UnsupportedOperationException();
		}
		@Override
		public OptionalInt getFixedSize(){
			return OptionalInt.empty();
		}
		@Override
		public OptionalInt getMaxSize(){
			return OptionalInt.empty();
		}
	};
	
	@Nullable
	static <L extends ReaderWriter> L getInstance(Class<?> containerClass, Class<L> rwClass, String[] args){
		if(rwClass==ReaderWriter.class) return null;
		
		return List.<AbstractMap.SimpleEntry<Supplier<Stream<Class<?>>>, Supplier<Stream<Object>>>>of(
			new AbstractMap.SimpleEntry<>(
				()->Stream.concat(Stream.of(Class.class), Arrays.stream(args).map(o->String.class)),
				()->Stream.concat(Stream.of(containerClass), Arrays.stream(args))),
			new AbstractMap.SimpleEntry<>(
				()->Stream.of(Class.class, String[].class),
				()->Stream.of(containerClass, args)),
			new AbstractMap.SimpleEntry<>(
				()->Arrays.stream(args).map(o->String.class),
				()->Arrays.stream(args)),
			new AbstractMap.SimpleEntry<>(
				()->Stream.of(String[].class),
				()->Stream.of(args))
		).stream()
		 .map(e->{
			 try{
				 return rwClass.getConstructor(e.getKey().get().toArray(Class[]::new))
				               .newInstance(e.getValue().get().toArray(Object[]::new));
			 }catch(ReflectiveOperationException ignored){
				 return null;
			 }
		 })
		 .filter(Objects::nonNull)
		 .findFirst()
		 .orElseThrow();
		
	}
	
	T read(Object targetObj, ContentReader source, T oldValue) throws IOException;
	
	void write(Object targetObj, ContentWriter target, T source) throws IOException;
	
	long mapSize(Object targetObj, T source);
	
	OptionalInt getFixedSize();
	
	OptionalInt getMaxSize();
}
