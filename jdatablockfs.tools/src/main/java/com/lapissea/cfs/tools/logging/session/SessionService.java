package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOHook;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.IOFileData;
import com.lapissea.cfs.objects.Blob;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.utils.ClosableLock;
import com.lapissea.cfs.utils.OptionalPP;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.LongStream;

public abstract sealed class SessionService implements Closeable{
	
	public abstract static class Writer implements Closeable, IOHook{
		private final String name;
		protected Writer(String name){ this.name = name; }
		
		abstract void write(IOSnapshot snap) throws IOException;
		
		private long                        frameId;
		private Optional<Instant>           lastTime  = Optional.empty();
		private OptionalPP<IOSnapshot.Full> lastFrame = OptionalPP.empty();
		
		private record FramePair(IOSnapshot.Full newFrame, OptionalPP<IOSnapshot.Full> lastFrame){ }
		
		@Override
		public void writeEvent(IOInterface data, LongStream writeIdx) throws IOException{
			
			var p = computeRelation(data, writeIdx);
			
			syncSubmit(p);
		}
		
		private final Lock relationLock = new ReentrantLock();
		private FramePair computeRelation(IOInterface data, LongStream writeIdx) throws IOException{
			relationLock.lock();
			try{
				var now      = Instant.now();
				var newFrame = IOSnapshot.Full.snap(frameId++, lastTime, now, data, writeIdx);
				var lastF    = lastFrame;
				lastTime = Optional.of(now);
				lastFrame = OptionalPP.of(newFrame);
				return new FramePair(newFrame, lastF);
			}finally{
				relationLock.unlock();
			}
		}
		
		private void syncSubmit(FramePair pair) throws IOException{
			write(pair.lastFrame.map(last -> IOSnapshot.Diff.make(last, pair.newFrame)).orElse(pair.newFrame));
		}
		
		@Override
		public void close() throws IOException{ }//TODO
		
		@Override
		public String toString(){ return "Writer{" + name + "}"; }
		public String getName(){ return name; }
	}
	
	public static final class OnDisk extends SessionService{
		
		private final class DWriter extends Writer{
			
			private final IOList<Frame<?>> frames;
			
			private DWriter(String name) throws IOException{
				super(name);
				frames = globalLock.sync(() -> info.sessions().computeIfAbsent(name, () -> {
					var frames = IOInstance.Def.of(SessionsInfo.Frames.class);
					frames.allocateNulls(prov);
					return frames;
				})).frames();
			}
			
			@Override
			protected void write(IOSnapshot snap) throws IOException{
				switch(snap){
					case IOSnapshot.Full full -> {
						var blob = globalLock.sync(() -> Blob.request(prov, full.buff.length));
						blob.write(true, full.buff);
						push(new Frame.Full(full.timeDelta, full.stacktrace, blob, List.copyOf(full.writeRanges)));
					}
					case IOSnapshot.Diff diff -> {
						var block = diff.changes.stream().map(r -> Frame.Incremental.IncBlock.of(r.off(), r.data())).toList();
						push(new Frame.Incremental(diff.timeDelta, diff.stackTrace, diff.parentId, diff.size, block));
					}
				}
				UtilL.sleep(50);//TODO: remove when done testing
			}
			private void push(Frame<?> frame) throws IOException{
				try(var ignore = globalLock.open()){
					frames.add(frame);
				}
			}
		}
		
		
		private final IOFileData   file;
		private final Cluster      prov;
		private final SessionsInfo info;
		
		private final ClosableLock globalLock = ClosableLock.reentrant();
		
		public OnDisk(File file) throws IOException{
			this.file = new IOFileData(file);
			prov = Cluster.init(this.file);
//			prov = Cluster.init(LoggedMemoryUtils.newLoggedMemory("ay", LoggedMemoryUtils.createLoggerFromConfig()));
			info = prov.getRootProvider()
			           .request(SessionsInfo.ROOT_ID, SessionsInfo.class);
			Thread.startVirtualThread(() -> {
				var uni = Utils.getSealedUniverse(Frame.class, false).orElseThrow();
				try(var ignore = globalLock.open()){
					for(var cls : uni.universe()){
						prov.getTypeDb().toID(uni.root(), cls, true);
					}
				}catch(IOException e){
					e.printStackTrace();
				}
			});
		}
		
		@Override
		public Writer openSession(String name) throws IOException{
			return new DWriter(name);
		}
		
		@Override
		public void close() throws IOException{
			file.close();
		}
	}
	
	public record ConnectionInfo(InetAddress address, Duration timeout){ }
	
	public static final class OverNetwork extends SessionService{
		
		@Override
		public Writer openSession(String name){
			throw NotImplementedException.infer();//TODO: implement OverNetwork.openSession()
		}
		@Override
		public void close() throws IOException{
			throw NotImplementedException.infer();//TODO: implement OverNetwork.close()
		}
	}
	
	public static SessionService of(File file) throws IOException{
		return new OnDisk(file);
	}
	
	public static SessionService of(ConnectionInfo info){
		throw new NotImplementedException();//TODO
	}
	
	public final Writer openSession() throws IOException{ return openSession("default"); }
	public abstract Writer openSession(String name) throws IOException;
}
