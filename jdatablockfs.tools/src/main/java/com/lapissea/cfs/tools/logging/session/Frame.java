package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.objects.Blob;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public abstract sealed class Frame<Self extends Frame<Self>> extends IOInstance.Managed<Self>{
	
	static final class Full extends Frame<Full>{
		@IOValue
		private Blob          data;
		@IOValue
		private List<IORange> writes;
		
		public Full(){ }
		
		public Full(Optional<Duration> timeDelta, IOStackTrace stackTrace, Blob data, List<IORange> writes){
			super(timeDelta, stackTrace);
			this.data = data;
			this.writes = writes;
		}
		public Blob getData(){ return data; }
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
		
		public Incremental(Optional<Duration> timeDelta, IOStackTrace stacktrace, long parentFrame, long newSize, List<IncBlock> data){
			super(timeDelta, stacktrace);
			this.parentFrame = parentFrame;
			this.newSize = newSize;
			this.data = data;
		}
		public long getParentFrame()   { return parentFrame; }
		public long getNewSize()       { return newSize; }
		public List<IncBlock> getData(){ return data; }
	}
	
	@IOValue
	@IONullability(NULLABLE)
	private Duration timeDelta;//TODO: add support for optional
	
	@IOValue
	protected IOStackTrace stacktrace;
	
	public Frame(){ }
	
	public Frame(Optional<Duration> timeDelta, IOStackTrace stacktrace){
		this.timeDelta = timeDelta.orElse(null);
		this.stacktrace = stacktrace;
	}
	
	public Duration getTimeDelta(){ return timeDelta; }
	public IOStackTrace getStacktrace(){
		return stacktrace;
	}
}
