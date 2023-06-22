package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.objects.Blob;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.time.Duration;
import java.util.List;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public abstract sealed class Frame<Self extends Frame<Self>> extends IOInstance.Managed<Self>{
	
	static final class Full extends Frame<Full>{
		@IOValue
		private Blob          data;
		@IOValue
		private List<IORange> writes;
	}
	
	static final class Incremental extends Frame<Incremental>{
		
		interface IncBlock extends Def<Incremental.IncBlock>{
			@IODependency.VirtualNumSize
			@IOValue.Unsigned
			long start();
			Blob data();
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
	}
	
	@IOValue
	@IONullability(NULLABLE)
	private Duration timeDelta;
	@IOValue
	private String   stackTrace;
	
	public Duration getTimeDelta(){ return timeDelta; }
	public String getStackTrace() { return stackTrace; }
}
