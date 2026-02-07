package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.inspect.display.grid.DataPos;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.IO;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldInlineObject;

import java.io.IOException;

public abstract class FieldInspectRead{
	
	public record ReferenceInfo(DataPos.Sized origin, DataPos ref, Class<?> type, FieldReader.ResSet<?> value){ }
	
	public static final class ReadCtx<T extends IOInstance<T>>{
		
		public final String             path;
		public final DataProvider       dataProvider;
		public final IOField<T, Object> field;
		public final VarPool<T>         ioPool;
		public final GenericContext     genericContext;
		public final RandomIO           src;
		public final T                  inst;
		public final boolean            unmanagedStage;
		public final StructPipe<T>      parentPipe;
		
		public ReadCtx(
			String path, DataProvider dataProvider, IOField<T, ?> field, VarPool<T> ioPool,
			GenericContext genericContext, RandomIO src, T inst, boolean unmanagedStage,
			StructPipe<T> parentPipe
		){
			this.path = path;
			this.dataProvider = dataProvider;
			this.field = (IOField<T, Object>)field;
			this.ioPool = ioPool;
			this.genericContext = genericContext;
			this.src = src;
			this.inst = inst;
			this.unmanagedStage = unmanagedStage;
			this.parentPipe = parentPipe;
		}
		
		public ReadCtx<T> withField(IOField<T, ?> field, boolean unmanagedStage){
			return new ReadCtx<>(path, dataProvider, field, ioPool, genericContext, src, inst, unmanagedStage, parentPipe);
		}
		
		public Object getVal(){
			return field.get(ioPool, inst);
		}
	}
	
	
	protected static <T extends IOInstance<T>> StructPipe<T> getStructPipe(ReadCtx<T> ctx, IOInstance fieldValue){
		if(ctx.unmanagedStage){
			return ((IOInstance.Unmanaged)ctx.inst).getFieldPipe(ctx.field, fieldValue);
		}else{
			if(ctx.field instanceof IOFieldInlineObject obj){
				return obj.getInstancePipe();
			}
			return StructPipe.of(ctx.parentPipe.getClass(), fieldValue.getThisStruct());
		}
	}
	
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> stdRes(ReadCtx<T> ctx) throws IOException{
		var pos = readOrSkip(ctx);
		
		var value = ctx.getVal();
		
		return FieldReader.res(ctx.field, value, pos);
	}
	
	public <T extends IOInstance<T>> DataPos.Sized readOrSkip(ReadCtx<T> ctx) throws IOException{
		var start = ctx.src.getPos();
		if(ctx.field instanceof IO.DisabledIO<?>){
			ctx.src.skip(ctx.field.getSizeDescriptor().calcUnknown(ctx.ioPool, ctx.dataProvider, ctx.inst, WordSpace.BYTE));
		}else{
			ctx.field.read(ctx.ioPool, ctx.dataProvider, ctx.src, ctx.inst, null);
		}
		var end = ctx.src.getPos();
		return defaultPos(ctx.src, start, end);
	}
	
	public DataPos.Sized defaultPos(RandomIO src, long start, long end){
		ChunkPointer    ptr   = tryGetPtr(src);
		DrawUtils.Range range = DrawUtils.Range.fromStartEnd(start, end);
		return new DataPos.Sized(ptr, range);
	}
	
	public ChunkPointer tryGetPtr(RandomIO src){
		ChunkPointer ptr;
		if(src instanceof ChunkChainIO io){
			ptr = io.head.getPtr();
		}else{
			ptr = ChunkPointer.NULL;
		}
		return ptr;
	}
	
	public abstract <T extends IOInstance<T>> FieldReader.Res<T, ?> read(ReadCtx<T> ctx) throws IOException;
}
