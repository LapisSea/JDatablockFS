package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.objects.Blob;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOCompression;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.lapissea.cfs.type.field.annotations.IOCompression.Type.LZ4;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public abstract sealed class Frame<Self extends Frame<Self>> extends IOInstance.Managed<Self>{
	
	public static final class StacktraceString extends IOInstance.Managed<StacktraceString>{
		
		@IOValue
		@IOCompression(LZ4)
		private byte[] bb;
		
		@IOValue
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private int start;
		
		public StacktraceString(){ }
		public StacktraceString(int start, String data){
			this.start = start;
			bb = data.getBytes(StandardCharsets.UTF_8);
		}
		
		private WeakReference<String> cache = new WeakReference<>(null);
		
		@Override
		public String toString(){
			var c = cache.get();
			return c != null? c : (start == 0? "" : "? ... ") + new String(bb, StandardCharsets.UTF_8);
		}
	}
	
	static final class Full extends Frame<Full>{
		@IOValue
		private Blob          data;
		@IOValue
		private List<IORange> writes;
		
		public Full(){ }
		
		public Full(Optional<Duration> timeDelta, String stackTrace, Blob data, List<IORange> writes){
			super(timeDelta, new StacktraceString(0, stackTrace));
			this.data = data;
			this.writes = writes;
		}
		public String getStacktrace(){
			var c = stacktrace.cache.get();
			if(c != null) return c;
			c = new String(stacktrace.bb, StandardCharsets.UTF_8);
			stacktrace.cache = new WeakReference<>(c);
			return c;
		}
	}
	
	static final class Incremental extends Frame<Incremental>{
		
		@Def.Order({"start", "data"})
		interface IncBlock extends Def<Incremental.IncBlock>{
			@IODependency.VirtualNumSize
			@IOValue.Unsigned
			long start();
			byte[] data();
			
			static IncBlock of(long start, byte[] data){
				return IOInstance.Def.of(IncBlock.class, start, data);
			}
			
		}
		
		@IOValue
		@IODependency.VirtualNumSize
		@IOValue.Unsigned
		private long                       parentFrame;
		@IOValue
		@IODependency.VirtualNumSize
		private long                       newSize;
		@IOValue
		private List<Incremental.IncBlock> data;
		
		public Incremental(){ }
		
		public Incremental(Optional<Duration> timeDelta, StacktraceString stacktrace, long parentFrame, long newSize, List<IncBlock> data){
			super(timeDelta, stacktrace);
			this.parentFrame = parentFrame;
			this.newSize = newSize;
			this.data = data;
		}
		
		public String getStacktrace(TimelineInfo timeline){
			var c = stacktrace.cache.get();
			if(c != null) return c;
			var parentStacktrace = switch(timeline.getById(parentFrame)){
				case Frame.Full full -> full.getStacktrace();
				case Frame.Incremental incremental -> incremental.getStacktrace(timeline);
			};
			var main = new String(stacktrace.bb, StandardCharsets.UTF_8);
			c = parentStacktrace.substring(stacktrace.start) + main;
			
			stacktrace.cache = new WeakReference<>(c);
			return c;
		}
	}
	
	@IOValue
	@IONullability(NULLABLE)
	private Duration timeDelta;//TODO: add support for optional
	
	@IOValue
	protected StacktraceString stacktrace;
	
	public Frame(){ }
	
	public Frame(Optional<Duration> timeDelta, StacktraceString stacktrace){
		this.timeDelta = timeDelta.orElse(null);
		this.stacktrace = stacktrace;
	}
	
	public Duration getTimeDelta(){ return timeDelta; }
}
