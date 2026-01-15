package com.lapissea.dfs.inspect.display.grid;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.inspect.display.grid.read.FieldInspectRead;
import com.lapissea.dfs.inspect.display.grid.read.ManagedRefInspectRead;
import com.lapissea.dfs.inspect.display.grid.read.SimpleReadInspectRead;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FieldReader{
	
	public record Res<T extends IOInstance<T>, V>(IOField<T, V> field, V value, DataPos.Sized pos){ }
	
	public record ResSet<T extends IOInstance<T>>(T value, long size, List<Res<T, ?>> fields){ }
	
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
			
			//noinspection unchecked
			fields.add(new Res<>((IOField<Chunk, ? super Object>)field, value, DataPos.Sized.ofRange(valueOffset, valueSize)));
		}
		//noinspection unchecked
		return (ResSet<T>)new ResSet<>(ch, pos - offset, fields);
	}
	
	public static <T extends IOInstance<T>> ResSet<T> readFields(DataProvider dataProvider, StructPipe<T> pipe, DataPos pos) throws IOException{
		if(pipe.getType().getType() == Chunk.class){
			assert pos.ptr().isNull();
			return getChunkFields(dataProvider, pipe, pos.offset());
		}
		
		List<Res<T, ?>> fields = new ArrayList<>(pipe.getSpecificFields().size());
		
		try(RandomIO src = pos.open(dataProvider)){
			var ioPool = pipe.makeIOPool();
			T   inst   = pipe.getType().make();
			
			var lastPos = src.getPos();
			var start   = lastPos;
			
			for(var field : pipe.getSpecificFields()){
				
				FieldInspectRead reader = getReader(field);
				
				//noinspection unchecked
				var res = reader.read((IOField<T, Object>)field, ioPool, dataProvider, src, inst);
				var ir  = (Res<T, ?>)res.inlineRes();
				fields.add(ir);
				lastPos = ir.pos.range().to();
				
				if(res.referenceInfo() instanceof FieldInspectRead.ReferenceInfo(Reference ref)){
				
				}
			}
			return new ResSet<>(inst, lastPos - start, fields);
		}
	}
	
	private static final Map<Class<IOField<?, ?>>, FieldInspectRead> FIELD_IMPLS = Map.of(
	
	);
	
	private static FieldInspectRead getReader(IOField<?, ?> field){
		var type = field.getClass();
		{
			var reader = FIELD_IMPLS.get(type);
			if(reader != null) return reader;
		}
		
		if(field instanceof RefField<?, ?> ref){
			return new ManagedRefInspectRead();
		}
		
		return new SimpleReadInspectRead();
	}
}
