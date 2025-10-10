package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.Objects;

import static com.lapissea.dfs.objects.text.AutoText.Info.NEEDS_POOL;
import static com.lapissea.dfs.objects.text.AutoText.Info.PIPE;
import static com.lapissea.dfs.objects.text.AutoText.Info.STRUCT;

@StructPipe.Special
public final class AutoText extends IOInstance.Managed<AutoText> implements CharSequence{
	
	public static final class Info{
		public static final Struct<AutoText>     STRUCT     = Struct.of(AutoText.class);
		public static final StructPipe<AutoText> PIPE       = StandardStructPipe.of(STRUCT);
		static final        boolean              NEEDS_POOL = !PIPE.getClass().getSimpleName().contains("AutoTextPipe");
	}
	
	public static final ObjectPipe.NoPool<String> STR_PIPE = new ObjectPipe.NoPool<>(){
		private BasicSizeDescriptor<String, Void> sizeDescriptor;
		
		private static BasicSizeDescriptor<String, Void> createSizeDescriptor(){
			var desc      = PIPE.getSizeDescriptor();
			var wordSpace = desc.getWordSpace();
			return BasicSizeDescriptor.Unknown.of(
				wordSpace, desc.getMin(), desc.getMax(),
				(pool, prov, value) -> {
					return desc.calcUnknown(NEEDS_POOL? PIPE.makeIOPool() : null, null, new AutoText(value), wordSpace);
				});
		}
		
		@Override
		public void write(DataProvider provider, ContentWriter dest, String instance) throws IOException{
			PIPE.write(provider, dest, new AutoText(instance));
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			PIPE.skip(provider, src, null);
		}
		@Override
		public String readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			return PIPE.readNew(provider, src, null).getData();
		}
		@Override
		public BasicSizeDescriptor<String, Void> getSizeDescriptor(){
			if(sizeDescriptor == null) sizeDescriptor = createSizeDescriptor();
			return sizeDescriptor;
		}
	};
	
	private String   data;
	private byte[]   dataSrc;
	@IOValue
	private Encoding encoding;
	@IOValue
	@IOValue.Unsigned
	@IODependency.VirtualNumSize(name = "numSize")
	private int      charCount;
	
	
	public AutoText(){
		super(STRUCT);
		data = "";
		encoding = Encoding.DEFAULT;
		charCount = 0;
	}
	
	public AutoText(String data){
		setData(data);
	}
	
	public void setData(@NotNull String newData){
		Objects.requireNonNull(newData);
		
		encoding = Encoding.findBest(newData);
		charCount = newData.length();
		data = newData;
		dataSrc = null;
	}
	
	@IOValue
	private void setCharCount(int charCount){
		this.charCount = charCount;
		dataSrc = null;
	}
	@IOValue
	private void setEncoding(Encoding encoding){
		this.encoding = encoding;
		dataSrc = null;
	}
	
	@IOValue
	@IODependency({"charCount", "encoding"})
	@IODependency.ArrayLenSize(name = "numSize")
	private byte[] getTextBytes() throws IOException{
		if(dataSrc == null){
			dataSrc = generateBytes();
		}
		return dataSrc;
	}
	
	private byte[] generateBytes() throws IOException{
		byte[] buff = new byte[encoding.calcSize(data)];
		encoding.write(new ContentOutputStream.BA(buff), data);
		return buff;
	}
	
	@IOValue
	private void setTextBytes(byte[] bytes) throws IOException{
		dataSrc = bytes;
		var buff = CharBuffer.allocate(charCount);
		encoding.read(new ContentInputStream.BA(bytes), buff);
		data = buff.flip().toString();
	}
	
	@NotNull
	@Override
	public String toString(){
		return data;
//		return set+": "+data;
	}
	
	@Override
	public int length(){
		return charCount;
	}
	@Override
	public CharSequence subSequence(int start, int end){
		return data.subSequence(start, end);
	}
	@Override
	public char charAt(int index){
		return data.charAt(index);
	}
	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(!(o instanceof AutoText text)) return false;
		return data.equals(text.data);
	}
	
	@Override
	public int hashCode(){
		return data.hashCode();
	}
	public String getData(){
		return data;
	}
}
