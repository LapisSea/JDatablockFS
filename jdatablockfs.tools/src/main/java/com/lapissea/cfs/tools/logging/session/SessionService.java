package com.lapissea.cfs.tools.logging.session;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.InvalidMagicID;
import com.lapissea.cfs.io.IOHook;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.IOFileData;
import com.lapissea.cfs.objects.Blob;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOTypeDB;
import com.lapissea.cfs.utils.ClosableLock;
import com.lapissea.cfs.utils.OptionalPP;
import com.lapissea.util.LogUtil;
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
	
	public static SessionService of(File file) throws IOException       { return new OnDisk(file); }
	public static SessionService of(IOInterface file) throws IOException{ return new OnIO(file); }
	public static SessionService of(ConnectionInfo info)                { throw new NotImplementedException();/*TODO*/ }
	
	static{
		//Preload IOSnapshot, saves around 100ms from startup
		Thread.startVirtualThread(() -> {
			var a = new IOSnapshot.Full(0, Optional.empty(), new Throwable(), List.of(), new byte[]{1, 2, 3, 4});
			var b = new IOSnapshot.Full(0, Optional.empty(), new Throwable(), List.of(), new byte[]{1, 4, 6, 1, 2});
			try{
				IOSnapshot.Diff.make(a, b);
			}catch(IOException e){
				e.printStackTrace();
			}
		});
	}
	
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
			var e = new Throwable();
			var p = computeRelation(e, data, writeIdx);
			syncSubmit(p);
		}
		
		private final Lock relationLock = new ReentrantLock();
		private FramePair computeRelation(Throwable e, IOInterface data, LongStream writeIdx) throws IOException{
			relationLock.lock();
			try{
				var now      = Instant.now();
				var newFrame = IOSnapshot.Full.snap(frameId++, e, lastTime, now, data, writeIdx);
				var lastF    = lastFrame;
				lastTime = Optional.of(now);
				lastFrame = OptionalPP.of(newFrame);
				return new FramePair(newFrame, lastF);
			}finally{
				relationLock.unlock();
			}
		}
		
		private void syncSubmit(FramePair pair) throws IOException{
//			var t1 = new NanoTimer.Simple();
//			t1.start();
			var frame = pair.lastFrame.map(last -> IOSnapshot.Diff.make(last, pair.newFrame)).orElse(pair.newFrame);
//			t1.end();
//
//			var t2 = new NanoTimer.Simple();
//			t2.start();
			write(frame);
//			t2.end();
//			LogUtil.println(t1.ms(), t2.ms());
		}
		
		@Override
		public void close() throws IOException{ }//TODO
		
		@Override
		public String toString(){ return "Writer{" + name + "}"; }
		public String getName(){ return name; }
	}
	
	private static final class SessionsInfoWriter extends Writer{
		
		private final DataProvider prov;
		private final ClosableLock globalLock;
		
		private final IOList<Frame<?>> frames;
		private final StringsIndex     stringsIndex;
		
		private SessionsInfoWriter(DataProvider prov, ClosableLock globalLock, SessionsInfo info, String name, boolean clear) throws IOException{
			super(name);
			this.prov = prov;
			this.globalLock = globalLock;
			
			var ses = this.globalLock.sync(() -> {
				var sessions = info.sessions();
				
				var session = sessions.computeIfAbsent(name, () -> {
					var frames = IOInstance.Def.of(SessionsInfo.Frames.class);
					frames.allocateNulls(this.prov);
					return frames;
				});
				if(clear && !session.frames().isEmpty() && !session.strings().isEmpty()){
					session.frames().clear();
					session.frames().trim();
					session.strings().clear();
					session.strings().trim();
				}
				return session;
			});
			frames = ses.frames();
			stringsIndex = new StringsIndex(ses.strings());
		}
		
		private Instant lastTime = Instant.now();
		@Override
		protected void write(IOSnapshot snap) throws IOException{
			switch(snap){
				case IOSnapshot.Full full -> {
					var blob = globalLock.sync(() -> Blob.request(prov, full.buff.length));
					blob.write(true, full.buff);
					
					var e = new IOStackTrace(stringsIndex, full.e, 0);
					push(new Frame.Full(full.timeDelta, e, blob, List.copyOf(full.writeRanges)));
				}
				case IOSnapshot.Diff diff -> {
					var block = diff.changes.stream().map(r -> Frame.Incremental.IncBlock.of(r.off(), r.data())).toList();
					var e     = new IOStackTrace(stringsIndex, diff.e, diff.eDiffBottomCount);
					push(new Frame.Incremental(diff.timeDelta, e, diff.parentId, diff.size, block));
				}
			}
			
			//throttle();//TODO: remove when done testing
		}
		private void throttle(){
			var now = Instant.now();
			var dur = Duration.between(lastTime, now);
			lastTime = now;
			
			var tim = Duration.ofMillis(50).minus(dur);
			if(tim.isPositive()) UtilL.sleep(tim.toMillis());
		}
		private void push(Frame<?> frame) throws IOException{
			try(var ignore = globalLock.open()){
				frames.add(frame);
			}
		}
	}
	
	private static final class OnDisk extends SessionService{
		
		private final IOFileData   ioFile;
		private final Cluster      prov;
		private final SessionsInfo info;
		
		private final ClosableLock globalLock = ClosableLock.reentrant();
		
		private OnDisk(File file) throws IOException{
			ioFile = new IOFileData(file);
			var res = OnIO.initIOInfo(globalLock, ioFile);
			this.prov = res.prov;
			this.info = res.info;
		}
		
		@Override
		public Writer openSession(String name) throws IOException{
			return new SessionsInfoWriter(prov, globalLock, info, name, true);
		}
		
		@Override
		public void close() throws IOException{
			ioFile.close();
		}
	}
	
	private static final class OnIO extends SessionService{
		
		private final Cluster      prov;
		private final SessionsInfo info;
		
		private final ClosableLock globalLock = ClosableLock.reentrant();
		
		private OnIO(IOInterface src) throws IOException{
			var res = initIOInfo(globalLock, src);
			this.prov = res.prov;
			this.info = res.info;
		}
		
		private record InfoProv(Cluster prov, SessionsInfo info){ }
		private static InfoProv initIOInfo(ClosableLock globalLock, IOInterface src) throws IOException{
			Cluster      prov;
			SessionsInfo info;
			try{
				prov = new Cluster(src);
				info = prov.getRootProvider().require(SessionsInfo.ROOT_ID, SessionsInfo.class);
				checkSessions(prov, globalLock, info);
			}catch(Throwable e){
				LogUtil.println("Clearing/creating DB for " + src);
				if(!(e instanceof InvalidMagicID)){
					e.printStackTrace();
				}
				
				prov = Cluster.init(src);
				info = prov.getRootProvider().request(SessionsInfo.ROOT_ID, SessionsInfo.class);
			}
			eagerFrameDefine(prov.getTypeDb(), globalLock);
			return new InfoProv(prov, info);
		}
		
		private static void eagerFrameDefine(IOTypeDB db, ClosableLock globalLock){
			Thread.startVirtualThread(() -> {
				var uni = Utils.getSealedUniverse(Frame.class, false).orElseThrow();
				try(var ignore = globalLock.open()){
					for(var cls : uni.universe()){
						db.toID(uni.root(), cls, true);
					}
				}catch(IOException e){
					e.printStackTrace();
				}
			});
		}
		
		private static void checkSessions(Cluster prov, ClosableLock globalLock, SessionsInfo info) throws IOException{
			for(var e : info.sessions()){
				new SessionsInfoWriter(prov, globalLock, info, e.getKey(), false).close();
				LogUtil.println(e.getKey(), "ok");
			}
		}
		
		@Override
		public Writer openSession(String name) throws IOException{
			return new SessionsInfoWriter(prov, globalLock, info, name, true);
		}
		
		@Override
		public void close() throws IOException{ }
	}
	
	public record ConnectionInfo(InetAddress address, Duration timeout){ }
	
	private static final class OverNetwork extends SessionService{
		
		@Override
		public Writer openSession(String name){
			throw NotImplementedException.infer();//TODO: implement OverNetwork.openSession()
		}
		@Override
		public void close() throws IOException{
			throw NotImplementedException.infer();//TODO: implement OverNetwork.close()
		}
	}
	
	public final Writer openSession() throws IOException{ return openSession("default"); }
	public abstract Writer openSession(String name) throws IOException;
}
