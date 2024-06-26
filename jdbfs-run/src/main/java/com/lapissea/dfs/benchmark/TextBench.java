package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.objects.text.AutoText;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;


@Warmup(iterations = 6, time = 1500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 6, time = 4000, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class TextBench{
	
	private final AutoText t = new AutoText();
	private final byte[]   bb, dst;
	private final StringBuilder sb = new StringBuilder();
	
	public TextBench(){
		try{
			byte[] b = new byte[81];
			
			var    arr = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);
			Random r   = new Random(100);
			for(int i = 0; i<b.length; i++){
				b[i] = arr[r.nextInt(arr.length)];
			}
//			b[0]='+';
			
			t.setData(new String(b));
			var buff = new ContentOutputBuilder();
			t.writeTextBytes(buff);
			bb = buff.toByteArray();
			dst = new byte[bb.length];
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
	}
	
	@Benchmark
	public void write() throws IOException{
		t.writeTextBytes(new ContentOutputStream.BA(dst));
	}
	
	@Benchmark
	public void read() throws IOException{
		sb.setLength(0);
		t.readTextBytes(new ContentInputStream.BA(bb), sb);
	}
}
