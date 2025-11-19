package com.lapissea.dfs.inspect.display.grid;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class FieldReader{
	
	record Res<T extends IOInstance<T>, V>(IOField<T, V> field, V value, ChunkPointer block, DrawUtils.Range range){ }
	
	record ResSet<T extends IOInstance<T>>(T value, List<Res<T, ?>> fields){ }
	
	private static <T extends IOInstance<T>> ResSet<T> getChunkFields(DataProvider dataProvider, StructPipe<T> pipe, long offset) throws IOException{
		var ch     = Chunk.readChunk(dataProvider, ChunkPointer.of(offset));
		var ioPool = Chunk.PIPE.makeIOPool();
		var pos    = offset;
		
		List<Res<Chunk, ?>> fields = new ArrayList<>(pipe.getSpecificFields().size());
		for(var field : Chunk.PIPE.getSpecificFields()){
			var valueSize   = field.getSizeDescriptor().calcUnknown(ioPool, dataProvider, ch, WordSpace.BYTE);
			var valueOffset = pos;
			pos += valueSize;
			Object value = field instanceof BitFieldMerger? null : field.get(ioPool, ch);
			
			//noinspection unchecked
			fields.add(new Res<>((IOField<Chunk, ? super Object>)field, value, ChunkPointer.NULL, DrawUtils.Range.fromSize(valueOffset, valueSize)));
		}
		//noinspection unchecked
		return (ResSet<T>)new ResSet<>(ch, fields);
	}
	static <T extends IOInstance<T>> ResSet<T> readFields(DataProvider dataProvider, StructPipe<T> pipe, ChunkPointer ptr, long offset) throws IOException{
		if(pipe.getType().getType() == Chunk.class){
			return getChunkFields(dataProvider, pipe, offset);
		}
		
		List<Res<T, ?>> fields = new ArrayList<>(pipe.getSpecificFields().size());
		
		RandomIO src;
		if(ptr.isNull()) src = dataProvider.getSource().ioAt(offset);
		else src = ptr.dereference(dataProvider).ioAt(offset);
		try(src){
			var ioPool  = pipe.makeIOPool();
			T   inst    = pipe.getType().make();
			var lastPos = src.getPos();
			for(var field : pipe.getSpecificFields()){
				field.read(ioPool, dataProvider, src, inst, null);
				if(field instanceof BitFieldMerger<?>){
					lastPos = src.getPos();
					continue;
				}
				var value  = field.get(ioPool, inst);
				var newPos = src.getPos();
				//noinspection unchecked
				fields.add(new Res<>((IOField<T, ? super Object>)field, value, ptr, new DrawUtils.Range(lastPos, newPos)));
				lastPos = newPos;
			}
			return new ResSet<>(inst, fields);
		}
	}
	
}
