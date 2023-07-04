package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.io.IOInterface;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

abstract sealed class IOSnapshot{
	
	private static String errorToStr(Throwable e){
		var b = new StringBuffer();
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
	
	static final class Full extends IOSnapshot{
		
		public static Full snap(long frameId, Throwable e, Optional<Instant> lastFrame, Instant currentFrame, IOInterface data, LongStream writeIdx) throws IOException{
			var duration = lastFrame.map(l -> Duration.between(l, currentFrame));
			
			var bb          = data.readAll();
			var writeRanges = IORange.fromIdx(writeIdx);
			
			return new Full(frameId, duration, e, writeRanges, bb);
		}
		
		final byte[] buff;
		
		Full(long frameId, Optional<Duration> timeDelta, Throwable e, List<IORange> writeRanges, byte[] buff){
			super(frameId, timeDelta, e, writeRanges);
			this.buff = buff;
		}
	}
	
	static final class Diff extends IOSnapshot{
		
		record DiffRange(long off, byte[] data){
			private static DiffRange of(byte[] data, int start, int end){
				var siz = end - start;
				var bb  = new byte[siz];
				System.arraycopy(data, start, bb, 0, siz);
				return new DiffRange(start, bb);
			}
		}
		
		static IOSnapshot make(IOSnapshot.Full last, IOSnapshot.Full current) throws IOException{
			var    ranges = new ArrayList<DiffRange>();
			byte[] lb     = last.buff, cb = current.buff;
			int    lSiz   = lb.length, cSiz = cb.length;
			
			int start = -1, end = -1;
			
			for(int i = 0, j = Math.min(lSiz, cSiz); i<j; i++){
				byte a = lb[i], b = cb[i];
				if(a != b){
					if(end == -1){
						start = i;
						end = i + 1;
						continue;
					}
					end++;
					continue;
				}
				if(end != -1){
					ranges.add(DiffRange.of(cb, start, end));
					end = -1;
				}
			}
			
			if(cSiz>lSiz){
				ranges.add(DiffRange.of(cb, lSiz, cSiz));
			}
			
			return new Diff(current.frameId, current.timeDelta, current.e, current.writeRanges, ranges, cSiz, last.frameId);
		}
		
		final List<DiffRange> changes;
		final long            size;
		final long            parentId;
		
		
		Diff(long frameId, Optional<Duration> timeDelta, Throwable e, List<IORange> writeRanges, List<DiffRange> changes, long size, long parentId){
			super(frameId, timeDelta, e, writeRanges);
			this.changes = changes;
			this.size = size;
			this.parentId = parentId;
		}
	}
	
	public final long               frameId;
	public final Optional<Duration> timeDelta;
	public final Throwable          e;
	public final List<IORange>      writeRanges;
	
	protected IOSnapshot(long frameId, Optional<Duration> timeDelta, Throwable e, List<IORange> writeRanges){
		this.frameId = frameId;
		this.timeDelta = timeDelta;
		this.e = e;
		this.writeRanges = writeRanges;
	}
}
