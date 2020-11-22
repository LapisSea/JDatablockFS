package com.lapissea.cfs.io;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.util.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface ReaderWriter<T>{
	
	ReaderWriter<?> DUMMY=new ReaderWriter<>(){
		@Override
		public Object read(Object targetObj, Cluster cluster, ContentReader source, Object oldValue){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public void write(Object targetObj, Cluster cluster, ContentWriter target, Object source){
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
	
	/**
	 * finds and calls constructor of [parent], [varargs]
	 */
	@Nullable
	static <L extends ReaderWriter<?>> L getInstance(Class<?> containerClass, Type targetType, Class<L> rwClass, String[] args){
		if((Object)rwClass==ReaderWriter.class) return null;
		return Utils.findConstructorInstance(rwClass, List.of(
			new AbstractMap.SimpleEntry<>(
				()->Stream.of(
					Stream.of(Class.class),
					Stream.of(Type.class),
					IntStream.range(0, args.length).mapToObj(o->String.class)
				             )
				          .flatMap(s->s),
				()->Stream.of(
					Stream.of(containerClass),
					Stream.of(targetType),
					Arrays.stream(args)
				             )
				          .flatMap(s->s)),
			
			new AbstractMap.SimpleEntry<>(
				()->Stream.concat(
					Stream.of(Type.class),
					Arrays.stream(args).map(o->String.class)
				                 ),
				()->Stream.concat(
					Stream.of(targetType),
					Arrays.stream(args)
				                 )),
			
			new AbstractMap.SimpleEntry<>(
				()->Stream.concat(
					Stream.of(Class.class),
					Arrays.stream(args).map(o->String.class)
				                 ),
				()->Stream.concat(
					Stream.of(containerClass),
					Arrays.stream(args)
				                 )),
			new AbstractMap.SimpleEntry<>(
				()->Stream.of(Class.class, String[].class),
				()->Stream.of(containerClass, args)),
			new AbstractMap.SimpleEntry<>(
				()->Arrays.stream(args).map(o->String.class),
				()->Arrays.stream(args)),
			new AbstractMap.SimpleEntry<>(
				()->Stream.of(String[].class),
				()->Stream.of(args))
		                                                     ));
		
	}
	
	T read(Object targetObj, Cluster cluster, ContentReader source, T oldValue) throws IOException;
	
	void write(Object targetObj, Cluster cluster, ContentWriter target, T source) throws IOException;
	
	long mapSize(Object targetObj, T source);
	
	OptionalInt getFixedSize();
	
	OptionalInt getMaxSize();
}
