package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.text.Encoding.CharEncoding;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.util.NotNull;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalLong;

@StructPipe.Special
public final class AutoText extends IOInstance.Managed<AutoText> implements CharSequence{
	
	public static final Struct<AutoText> STRUCT = Struct.of(AutoText.class);
	
	private static final class AutoTextPipe extends StandardStructPipe<AutoText>{
		public AutoTextPipe(){
			super(STRUCT, true);
		}
		@Override
		protected SizeDescriptor<AutoText> createSizeDescriptor(){
			return SizeDescriptor.UnknownLambda.of(1, OptionalLong.empty(), (ioPool, prov, value) -> {
				var charCount = value.charCount;
				int textBytesCount;
				if(value.dataSrc != null) textBytesCount = value.dataSrc.length;
				else textBytesCount = value.encoding.calcSize(value.data);
				
				var numSize      = NumberSize.bySize(charCount);
				var textBytesLen = NumberSize.bySize(textBytesCount);
				
				return 1L + numSize.bytes + textBytesLen.bytes + textBytesCount;
			});
		}
	}
	
	static{
		StandardStructPipe.registerSpecialImpl(STRUCT, AutoTextPipe::new);
	}
	
	public static final StructPipe<AutoText> PIPE = StandardStructPipe.of(STRUCT);
	
	
	public static final ObjectPipe.NoPool<String> STR_PIPE = new ObjectPipe.NoPool<>(){
		private final BasicSizeDescriptor<String, Void> sizeDescriptor;
		
		{
			var desc = AutoText.PIPE.getSizeDescriptor();
			sizeDescriptor = BasicSizeDescriptor.Unknown.of(
				desc.getWordSpace(), desc.getMin(), desc.getMax(),
				(pool, prov, value) -> desc.calcUnknown(null, null, new AutoText(value), desc.getWordSpace()));
		}
		
		@Override
		public void write(DataProvider provider, ContentWriter dest, String instance) throws IOException{
			AutoText.PIPE.write(provider, dest, new AutoText(instance));
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			AutoText.PIPE.skip(provider, src, null);
		}
		@Override
		public String readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			return AutoText.PIPE.readNew(provider, src, null).getData();
		}
		@Override
		public BasicSizeDescriptor<String, Void> getSizeDescriptor(){ return sizeDescriptor; }
	};
	
	private String       data;
	private byte[]       dataSrc;
	@IOValue
	private CharEncoding encoding;
	@IOValue
	@IOValue.Unsigned
	@IODependency.VirtualNumSize(name = "numSize")
	private int          charCount;
	
	
	public AutoText(){
		super(STRUCT);
		data = "";
		encoding = CharEncoding.DEFAULT;
		charCount = 0;
	}
	
	public AutoText(String data){
		setData(data);
	}
	
	public void setData(@NotNull String newData){
		Objects.requireNonNull(newData);
		
		encoding = CharEncoding.findBest(newData);
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
	private void setEncoding(CharEncoding encoding){
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
		writeTextBytes(new ContentOutputStream.BA(buff));
		return buff;
	}
	
	@IOValue
	private void setTextBytes(byte[] bytes) throws IOException{
		dataSrc = bytes;
		StringBuilder sb = new StringBuilder(charCount);
		readTextBytes(new ContentInputStream.BA(bytes), sb);
		data = sb.toString();
	}
	
	public void writeTextBytes(ContentWriter dest) throws IOException{
		encoding.write(dest, data);
	}
	public void readTextBytes(ContentInputStream src, StringBuilder dest) throws IOException{
		encoding.read(src, charCount, dest);
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
