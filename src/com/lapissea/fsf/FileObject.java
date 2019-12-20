package com.lapissea.fsf;

import com.lapissea.util.function.BiLongConsumer;
import com.lapissea.util.function.FunctionOL;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

public abstract class FileObject{
	
	public static class FileObjectDef<T, V extends FileObject> implements ObjectDef<T>{
		private final Function<T, V>   getter;
		private final BiConsumer<V, T> setter;
		private final Function<T, V>   constructor;
		
		public FileObjectDef(Function<T, V> getter, BiConsumer<V, T> setter, Function<T, V> constructor){
			this.getter=getter;
			this.setter=setter;
			this.constructor=constructor;
		}
		
		@Override
		public void write(ContentOutputStream stream, T source) throws IOException{
			getter.apply(source).write(stream);
		}
		
		@Override
		public void read(ContentInputStream stream, T dest) throws IOException{
			{
				var val=getter.apply(dest);
				if(val!=null){
					val.read(stream);
					return;
				}
			}
			
			var val=constructor.apply(dest);
			val.read(stream);
			
			setter.accept(val, dest);
		}
		
		@Override
		public int length(T parent){
			return getter.apply(parent).length();
		}
	}
	
	public static class FileNumberDef<T, V extends Number> implements ObjectDef<T>{
		
		private final NumberSize        size;
		private final FunctionOL<T>     getter;
		private final BiLongConsumer<T> setter;
		
		public FileNumberDef(NumberSize size, FunctionOL<T> getter, BiLongConsumer<T> setter){
			this.size=size;
			this.getter=getter;
			this.setter=setter;
		}
		
		@Override
		public void write(ContentOutputStream stream, T source) throws IOException{
			size.write(stream, getter.apply(source));
		}
		
		@Override
		public void read(ContentInputStream stream, T dest) throws IOException{
			setter.accept(size.read(stream), dest);
		}
		
		@Override
		public int length(T parent){
			return size.bytes;
		}
	}
	
	public static class FileFlagDef<T> implements ObjectDef<T>{
		
		private final NumberSize                size;
		private final BiConsumer<FlagWriter, T> getter;
		private final BiConsumer<FlagReader, T> setter;
		
		public FileFlagDef(NumberSize size, BiConsumer<FlagWriter, T> getter, BiConsumer<FlagReader, T> setter){
			this.size=size;
			this.getter=getter;
			this.setter=setter;
		}
		
		@Override
		public void write(ContentOutputStream stream, T source) throws IOException{
			FlagWriter writer=new FlagWriter(size);
			getter.accept(writer, source);
			writer.export(stream);
		}
		
		@Override
		public void read(ContentInputStream stream, T dest) throws IOException{
			FlagReader reader=FlagReader.read(stream, size);
			setter.accept(reader, dest);
		}
		
		@Override
		public int length(T parent){
			return size.bytes;
		}
	}
	
	public interface ObjectDef<T>{
		void write(ContentOutputStream stream, T source) throws IOException;
		
		void read(ContentInputStream stream, T dest) throws IOException;
		
		int length(T parent);
	}
	
	public interface SequenceLayout<T>{
		void write(ContentOutputStream stream, T source) throws IOException;
		
		void read(ContentInputStream stream, T dest) throws IOException;
		
		int length(T source);
	}
	
	@SuppressWarnings("unchecked")
	public static <T, Vs> SequenceLayout<T> sequenceBuilder(List<ObjectDef<T>> values){
		ObjectDef<T>[] arr=values.toArray(ObjectDef[]::new);
		
		return new SequenceLayout<>(){
			
			@Override
			public void write(ContentOutputStream stream, T source) throws IOException{
				for(var value : arr){
					value.write(stream, source);
				}
			}
			
			@Override
			public void read(ContentInputStream stream, T dest) throws IOException{
				for(var value : arr){
					value.read(stream, dest);
				}
			}
			
			@Override
			public int length(T source){
				int sum=0;
				for(ObjectDef<T> v : arr){
					int length=v.length(source);
					sum+=length;
				}
				return sum;
			}
		};
	}
	
	public abstract void read(ContentInputStream dest) throws IOException;
	
	public abstract void write(ContentOutputStream dest) throws IOException;
	
	public abstract int length();
	
}
