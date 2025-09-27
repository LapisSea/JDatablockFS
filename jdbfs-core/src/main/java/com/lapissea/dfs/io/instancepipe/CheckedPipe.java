package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public sealed interface CheckedPipe{
	
	final class Standard<T extends IOInstance<T>> extends StandardStructPipe<T> implements CheckedPipe{
		
		private final int[]                 count = new int[4];
		private final StandardStructPipe<T> check, test;
		
		public Standard(StandardStructPipe<T> check, StandardStructPipe<T> test){
			super(check.getType(), (type, structFields, testRun) -> new PipeFieldCompiler.Result<>(check.getSpecificFields()), STATE_DONE);
			this.check = check;
			this.test = test;
		}
		
		public StandardStructPipe<T> getUncheckedPipe(){
			return test;
		}
		
		@Override
		protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
			doWriteC(check, test, count, provider, dest, ioPool, instance);
		}
		@Override
		public T readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			return readNewC(check, test, count, provider, src, genericContext);
		}
		@Override
		protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			return doReadC(check, test, count, ioPool, provider, src, instance, genericContext);
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			skipC(check, test, count, provider, src, genericContext);
		}
	}
	
	final class Fixed<T extends IOInstance<T>> extends FixedStructPipe<T> implements CheckedPipe{
		
		private final int[]              count = new int[4];
		private final FixedStructPipe<T> check, test;
		
		public Fixed(FixedStructPipe<T> check, FixedStructPipe<T> test){
			super(check.getType(), (type, structFields, testRun) -> new PipeFieldCompiler.Result<>(check.getSpecificFields()), STATE_DONE);
			this.check = check;
			this.test = test;
		}
		
		@Override
		protected void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
			doWriteC(check, test, count, provider, dest, ioPool, instance);
		}
		@Override
		protected T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			return doReadC(check, test, count, ioPool, provider, src, instance, genericContext);
		}
		@Override
		public T readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			return readNewC(check, test, count, provider, src, genericContext);
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			skipC(check, test, count, provider, src, genericContext);
		}
	}
	
	
	int MAX_COUNT = 1000;
	
	private static <T extends IOInstance<T>> void doWriteC(StructPipe<T> check, StructPipe<T> test, int[] count, DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		if(count[0]>MAX_COUNT){
			test.doWrite(provider, dest, ioPool, instance);
			return;
		}
		count[0]++;
		checkedWrite(check, test, provider, dest, ioPool, instance);
	}
	private static <T extends IOInstance<T>> T doReadC(StructPipe<T> check, StructPipe<T> test, int[] count, VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(count[1]>MAX_COUNT){
			return test.doRead(ioPool, provider, src, instance, genericContext);
		}
		count[1]++;
		return checkedRead(check, test, ioPool, provider, src, instance, genericContext);
	}
	private static <T extends IOInstance<T>> T readNewC(StructPipe<T> check, StructPipe<T> test, int[] count, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		if(count[2]>MAX_COUNT){
			return test.readNew(provider, src, genericContext);
		}
		count[2]++;
		return checkedReadNew(check, test, provider, src, genericContext);
	}
	private static <T extends IOInstance<T>> void skipC(StructPipe<T> check, StructPipe<T> test, int[] count, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		if(count[3]>MAX_COUNT){
			test.skip(provider, src, genericContext);
			return;
		}
		count[3]++;
		checkedSkip(check, test, provider, src, genericContext);
	}
	
	private static <T extends IOInstance<T>> void checkedSkip(StructPipe<T> check, StructPipe<T> test, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var read = check.readNew(provider, src, genericContext);
		var buff = new ContentOutputBuilder();
		check.write(provider, buff, read);
		var ba = new ContentInputStream.BA(buff.toByteArray());
		test.skip(provider, ba, genericContext);
		if(ba.readRemaining().length != 0){
			throw new IllegalStateException("Test and check skips do not match! " + check);
		}
	}
	
	private static <T extends IOInstance<T>> void checkedWrite(StructPipe<T> check, StructPipe<T> test, DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException{
		var buff1 = new ContentOutputBuilder();
		var buff2 = new ContentOutputBuilder();
		test.doWrite(provider, buff2, ioPool, instance);
		check.doWrite(provider, buff1, ioPool, instance);
		
		var bb1 = buff1.toByteArray();
		var bb2 = buff2.toByteArray();
		if(!Arrays.equals(bb1, bb2)){
			throw new IllegalStateException(TextUtil.toString("Test and check do not match!", check, "\n", bb1, "\n", bb2));
		}
		dest.write(bb1);
	}
	
	private static <T extends IOInstance<T>> T checkedRead(StructPipe<T> check, StructPipe<T> test, VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var read = instance.clone();
		check.read(provider, src, read, genericContext);
		var buff = new ContentOutputBuilder();
		check.write(provider, buff, read);
		var ba = new ContentInputStream.BA(buff.toByteArray());
		test.doRead(ioPool, provider, ba, instance, genericContext);
		
		if(ba.readRemaining().length != 0 || !Objects.equals(read, instance)){
			throw new IllegalStateException(TextUtil.toString("Test and check do not match!", check, "\n", instance, "\n", read));
		}
		return instance;
	}
	
	private static <T extends IOInstance<T>> T checkedReadNew(StructPipe<T> check, StructPipe<T> test, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var instance = check.readNew(provider, src, genericContext);
		var buff     = new ContentOutputBuilder();
		check.write(provider, buff, instance);
		var ba   = new ContentInputStream.BA(buff.toByteArray());
		var read = test.readNew(provider, ba, genericContext);
		
		if(ba.readRemaining().length != 0 || !Objects.equals(read, instance)){
			throw new IllegalStateException(TextUtil.toString("Test and check do not match!", check, "\n", instance, "\n", read));
		}
		return instance;
	}
}
