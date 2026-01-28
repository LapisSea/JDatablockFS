package com.lapissea.dfs.inspect.display.grid;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.inspect.display.grid.read.BitMergerInspectRead;
import com.lapissea.dfs.inspect.display.grid.read.FieldInspectRead;
import com.lapissea.dfs.inspect.display.grid.read.ManagedRefInspectRead;
import com.lapissea.dfs.inspect.display.grid.read.SimpleReadInspectRead;
import com.lapissea.dfs.inspect.display.grid.read.UnmanagedRefInspectRead;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class FieldReader{
	
	public record Res<T extends IOInstance<T>, V>(
		IOField<T, V> field, V value, DataPos.Sized pos, FieldInspectRead.ReferenceInfo reference, ResSet<?> inner
	){
		public Res<T, V> withInner(ResSet<?> inner){
			return new Res<>(field, value, pos, reference, inner);
		}
		public Res<T, V> withRef(DataPos.Sized origin, DataPos ref, Class<?> type, FieldReader.ResSet<?> value){
			return withRef(new FieldInspectRead.ReferenceInfo(origin, ref, type, value));
		}
		public Res<T, V> withRef(FieldInspectRead.ReferenceInfo reference){
			return new Res<>(field, value, pos, reference, inner);
		}
	}
	public static <T extends IOInstance<T>, V> Res<T, V> res(IOField<T, V> field, V value, DataPos.Sized pos){
		return new Res<>(field, value, pos, null, null);
	}
	
	public record ResSet<T extends IOInstance<T>>(
		T value, DataPos.Sized pos, List<Res<T, ?>> fields
	){
		public static <T extends IOInstance<T>> ResSet<T> empty(){
			return new FieldReader.ResSet<>(null, DataPos.Sized.ofRange(0, 0), List.of());
		}
	}
	
	private static <T extends IOInstance<T>> ResSet<T> getChunkFields(DataProvider dataProvider, StructPipe<T> pipe, long offset) throws IOException{
		var ch     = dataProvider.getChunk(ChunkPointer.of(offset));
		var ioPool = Chunk.PIPE.makeIOPool();
		var pos    = offset;
		
		List<Res<Chunk, ?>> fields = new ArrayList<>(pipe.getSpecificFields().size());
		
		for(var field : Chunk.PIPE.getSpecificFields()){
			var valueSize   = field.getSizeDescriptor().calcUnknown(ioPool, dataProvider, ch, WordSpace.BYTE);
			var valueOffset = pos;
			pos += valueSize;
			Object value = field instanceof BitFieldMerger? null : field.get(ioPool, ch);
			
			var dataPos = DataPos.Sized.ofRange(valueOffset, valueSize);
			
			FieldInspectRead.ReferenceInfo ref = null;
			
			if(field.getType() == ChunkPointer.class && ch.hasNextPtr()){
				ref = new FieldInspectRead.ReferenceInfo(
					dataPos,
					DataPos.from(ch.getNextPtr()),
					ChunkPointer.class,
					ResSet.empty()
				);
			}
			
			//noinspection unchecked
			fields.add(new Res<>((IOField<Chunk, ? super Object>)field, value, dataPos, ref, null));
		}
		
		//noinspection unchecked
		return (ResSet<T>)new ResSet<>(ch, DataPos.Sized.ofRange(offset, pos - offset), fields);
	}
	
	public static <T extends IOInstance<T>> ResSet<T> readFields(
		DataProvider dataProvider, StructPipe<T> pipe, DataPos pos, GenericContext genericContext
	) throws IOException{
		if(pipe.getType().getType() == Chunk.class){
			assert pos.ptr().isNull();
			return getChunkFields(dataProvider, pipe, pos.offset());
		}
		
		return readManaged(dataProvider, pipe, pos, genericContext);
	}
	
	public static <T extends IOInstance<T>> ResSet<T> readManaged(
		DataProvider dataProvider, StructPipe<T> pipe, DataPos pos, GenericContext genericContext
	) throws IOException{
		List<Res<T, ?>> fields = new ArrayList<>(pipe.getSpecificFields().size());
		
		try(RandomIO src = pos.open(dataProvider)){
			var ioPool = pipe.makeIOPool();
			T   inst   = pipe.getType().make();
			
			var lastPos = src.getPos();
			var start   = lastPos;
			
			for(var field : pipe.getSpecificFields()){
				
				var res = readField(dataProvider, genericContext, field, ioPool, src, inst);
				fields.add(res);
				lastPos = res.pos.range().to();
			}
			
			return new ResSet<>(inst, pos.withSize(lastPos - start), fields);
		}
	}
	
	public static <T extends IOInstance.Unmanaged<T>> ResSet<T> readUnmanaged(
		DataProvider dataProvider, StructPipe<T> pipe, ChunkPointer pos, IOType type, GenericContext genericContext
	) throws IOException{
		List<Res<T, ?>> fields = new ArrayList<>();
		
		var identity = pos.dereference(dataProvider);
		
		try(RandomIO src = identity.io()){
			var ioPool = pipe.makeIOPool();
			
			T inst = ((Struct.Unmanaged<T>)pipe.getType()).make(dataProvider, identity, type);
			
			var lastPos = src.getPos();
			var start   = lastPos;
			
			for(var field : Iters.concat(pipe.getSpecificFields(), inst.listUnmanagedFields())){
				var res = readField(dataProvider, genericContext, field, ioPool, src, inst);
				fields.add(res);
				lastPos = res.pos.range().to();
			}
			
			return new ResSet<>(inst, new DataPos(pos, 0).withSize(lastPos - start), fields);
		}
	}
	
	private static <T extends IOInstance<T>> Res<T, ?> readField(DataProvider dataProvider, GenericContext genericContext, IOField<T, ?> field, VarPool<T> ioPool, RandomIO src, T inst) throws IOException{
		FieldInspectRead reader = getReader(field);
		//noinspection unchecked
		return reader.read((IOField<T, Object>)field, ioPool, dataProvider, src, inst, genericContext);
	}
	private static FieldInspectRead getReader(IOField<?, ?> field){
		if(field instanceof BitFieldMerger){
			return new BitMergerInspectRead();
		}
		
		if(field instanceof RefField<?, ?> ref){
			if(IOInstance.isUnmanaged(ref.getType())){
				return new UnmanagedRefInspectRead();
			}
			return new ManagedRefInspectRead();
		}
		
		return new SimpleReadInspectRead();
	}
}
