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
		
		public Full(Optional<Duration> timeDelta, String stackTrace, Blob data, List<IORange> writes){
			super(timeDelta, stackTrace);
			this.data = data;
			this.writes = writes;
		}
	}
	
	static final class Incremental extends Frame<Incremental>{
		
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
		
		public Incremental(Optional<Duration> timeDelta, String stackTrace, long parentFrame, long newSize, List<IncBlock> data){
			super(timeDelta, stackTrace);
			this.parentFrame = parentFrame;
			this.newSize = newSize;
			this.data = data;
		}
	}
	
	@IOValue
	@IONullability(NULLABLE)
	private Duration timeDelta;//TODO: add support for optional
	@IOValue
	private String   stackTrace;
	
	public Frame(){ }
	
	public Frame(Optional<Duration> timeDelta, String stackTrace){
		this.timeDelta = timeDelta.orElse(null);
		this.stackTrace = stackTrace;
	}
	
	public Duration getTimeDelta(){ return timeDelta; }
	public String getStackTrace() { return stackTrace; }
}
