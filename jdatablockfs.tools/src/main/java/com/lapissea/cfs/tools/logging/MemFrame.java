package com.lapissea.cfs.tools.logging;

import com.lapissea.util.TextUtil;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public final class MemFrame implements Serializable{
	
	public static MemFrame diff(MemFrame prev, MemFrame frame){
		if(prev == null) return null;
		var bs  = frame.bytes();
		var pbs = prev.bytes().length == bs.length? prev.bytes() : Arrays.copyOf(prev.bytes(), bs.length);
		if(pbs.length == 0) return null;
		
		class Range{
			final int from;
			int to;
			public Range(int i){
				this.from = i;
				this.to = i + 1;
			}
			@Override
			public String toString(){
				return "Range{" +
				       "from=" + from +
				       ", to=" + to +
				       '}';
			}
		}
		
		List<Range> allRanges;
		{
			var cores = Math.max(1, Runtime.getRuntime().availableProcessors());
			
			var siz = Math.max((int)Math.ceil(bs.length/(double)cores), 1024*8);
			
			var chunks = (int)Math.ceil(bs.length/(double)siz);
			allRanges = IntStream.range(0, chunks).parallel().mapToObj(chunk -> {
				var start = chunk*siz;
				var end   = Math.min(bs.length, (chunk + 1)*siz);
				
				List<Range> ranges = new ArrayList<>();
				
				for(int i = start; i<end; i++){
					var bp = pbs[i];
					var b  = bs[i];
					
					if(bp == b) continue;
					if(!ranges.isEmpty()){
						var r = ranges.get(ranges.size() - 1);
						if(r.to == i){
							r.to++;
							continue;
						}
					}
					
					ranges.add(new Range(i));
				}
				return ranges;
			}).reduce((l1, l2) -> {
				if(!l1.isEmpty() && !l2.isEmpty()){
					var a = l1.get(l1.size() - 1);
					var b = l2.get(0);
					if(a.to == b.from){
						a.to = b.to;
						l2 = l2.subList(1, l2.size());
					}
				}
				l1.addAll(l2);
				return l1;
			}).orElse(List.of());
		}
		
		if(allRanges.stream().mapToInt(e -> e.to - e.from).sum()>bs.length/2){
			return null;
		}
		
		var diffFrame = new MemFrame(frame.frameId(), frame.timeDelta(), prev, bs.length, allRanges.stream().map(range -> {
			var    siz   = range.to - range.from;
			byte[] chunk = new byte[siz];
			System.arraycopy(bs, range.from, chunk, 0, siz);
			return new DiffChunk(chunk, range.from);
		}).toList(), frame.ids(), frame.e());
		
		var check = diffFrame.bytes();
		
		if(!Arrays.equals(check, bs)){
			throw new RuntimeException(TextUtil.toString(check, "\n", bs));
		}
		
		return diffFrame;
	}
	
	private record DiffChunk(byte[] data, int pos) implements Serializable{ }
	
	private final MemFrame        prev;
	private final int             len;
	private final List<DiffChunk> diff;
	
	private final long   frameId;
	private final long   timeDelta;
	private final byte[] bytes;
	private final long[] ids;
	private final String e;
	
	public transient boolean askForCompress;
	
	private MemFrame(long frameId, long timeDelta, MemFrame prev, int len, List<DiffChunk> diff, long[] ids, String e){
		this.frameId = frameId;
		this.timeDelta = timeDelta;
		this.len = len;
		this.bytes = null;
		this.ids = ids;
		this.e = e;
		this.prev = prev;
		this.diff = diff;
	}
	public MemFrame(long frameId, long timeDelta, byte[] bytes, long[] ids, String e){
		this.frameId = frameId;
		this.timeDelta = timeDelta;
		this.bytes = bytes;
		this.ids = ids;
		this.e = e;
		this.len = -1;
		this.prev = null;
		this.diff = null;
	}
	
	public MemFrame(long frameId, long timeDelta, byte[] data, long[] ids, Throwable e){
		this(frameId, timeDelta, data, ids, errorToStr(e));
	}
	public static String errorToStr(Throwable e){
		StringBuffer b = new StringBuffer();
		
		e.printStackTrace(new PrintWriter(new Writer(){
			@Override
			public void write(char[] cbuf, int off, int len){ b.append(cbuf, off, len); }
			@Override
			public void flush(){ }
			@Override
			public void close(){ }
		}));
		
		
		return b.toString();
	}
	
	private transient WeakReference<byte[]> cachedDiff = new WeakReference<>(null);
	
	private byte[] calcDiff(){
		var diffBytes = Arrays.copyOf(this.prev.bytes(), len);
		
		for(DiffChunk(byte[] data, int pos) : diff){
			System.arraycopy(data, 0, diffBytes, pos, data.length);
		}
		
		return diffBytes;
	}
	
	public byte[] bytes(){
		if(prev == null) return bytes;
		
		var diff = cachedDiff.get();
		
		if(diff == null){
			diff = calcDiff();
			cachedDiff = new WeakReference<>(diff);
		}
		return diff;
	}
	public long[] ids(){ return ids; }
	public String e()  { return e; }
	public long frameId(){
		return frameId;
	}
	public long timeDelta(){
		return timeDelta;
	}
	@Override
	public boolean equals(Object obj){
		if(obj == this) return true;
		if(obj == null || obj.getClass() != this.getClass()) return false;
		var that = (MemFrame)obj;
		return Arrays.equals(this.ids, that.ids) &&
		       Objects.equals(this.e, that.e) &&
		       Arrays.equals(this.bytes(), that.bytes());
	}
	@Override
	public int hashCode(){
		return Objects.hash(e);
	}
	@Override
	public String toString(){
		return "MemFrame[" +
		       "ids=" + Arrays.toString(ids) + ", " +
		       "e=" + e + ']';
	}
	
}
