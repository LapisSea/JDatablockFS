package com.lapissea.fuzz.distributed;

import com.lapissea.fuzz.FuzzSequence;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class WireTest{
	private static final UUID         JOB      = UUID.fromString("00000000-0000-0000-0000-000000000123");
	private static final FuzzSequence SEQUENCE = new FuzzSequence(17, 2, 99, 10);
	
	private record Pair(Wire sender, Wire receiver) implements AutoCloseable{
		static Pair open() throws IOException{
			try(var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())){
				var socket   = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
				var accepted = server.accept();
				socket.setSoTimeout(5000);
				accepted.setSoTimeout(5000);
				return new Pair(new Wire(socket), new Wire(accepted));
			}
		}
		public void close() throws IOException{
			try{ sender.close(); }finally{ receiver.close(); }
		}
	}
	
	@Test
	public void allMessagesRoundTrip() throws Exception{
		var messages = new ArrayList<Wire.Message>(List.of(
			new Wire.Hello(Wire.VERSION, "token", "helper-č", 24, "bundle", 21),
			new Wire.Job(new byte[]{0, 1, -1}),
			new Wire.Work(new Wire.Assignment(JOB, 7), SEQUENCE),
			new Wire.Start(new Wire.Assignment(JOB, 7)),
			new Wire.StartAck(new Wire.Assignment(JOB, 7), true),
			new Wire.StartAck(new Wire.Assignment(JOB, 8), false),
			new Wire.Progress(new Wire.Assignment(JOB, 7), new RemoteSequenceResult.ExecutionStats(3, 100)),
			new Wire.Output(true, new byte[]{0, -1, 42}),
			new Wire.Output(false, new byte[0]),
			new Wire.Cancel(),
			new Wire.Cancel(JOB),
			new Wire.CleanupComplete(JOB, null),
			new Wire.CleanupComplete(JOB, "cleanup failed"),
			new Wire.Ping(),
			new Wire.CancelAssignment(new Wire.Assignment(JOB, 7)),
			new Wire.Demand(JOB, 2, 1)
		));
		for(var status : List.of(
			new RemoteSequenceResult.Ok(), new RemoteSequenceResult.Cancelled("cancelled"),
			new RemoteSequenceResult.InfrastructureError("disconnected"),
			new RemoteSequenceResult.Failed(new RemoteSequenceResult.RemoteFailure(
				19, "SomeException", "message", "trace\nline")
			))){
			messages.add(new Wire.Result(new RemoteSequenceResult(
				new Wire.Assignment(JOB, 7), SEQUENCE, status, RemoteSequenceResult.CapturedOutput.EMPTY,
				new RemoteSequenceResult.ExecutionStats(3, 100)
			)));
		}
		
		try(var pair = Pair.open()){
			for(var message : messages){
				pair.sender().send(message);
				var decoded = pair.receiver().receive();
				switch(message){
					case Wire.Job m -> assertEquals(((Wire.Job)decoded).recipe(), m.recipe());
					case Wire.Output m -> {
						assertEquals(((Wire.Output)decoded).error(), m.error());
						assertEquals(((Wire.Output)decoded).bytes(), m.bytes());
					}
					default -> assertEquals(decoded, message);
				}
			}
		}
	}
	
	@Test
	public void workEncodingKeepsStableLayout() throws Exception{
		var payload = new ByteArrayOutputStream();
		try(var out = new DataOutputStream(payload)){
			out.writeByte(3); // WORK tag.
			byte[] job = JOB.toString().getBytes(StandardCharsets.UTF_8);
			out.writeInt(job.length);
			out.write(job);
			out.writeLong(7);
			out.writeLong(17);
			out.writeLong(2);
			out.writeLong(99);
			out.writeInt(10);
		}
		
		try(var pair = Pair.open()){
			pair.sender().send(new Wire.Work(new Wire.Assignment(JOB, 7), SEQUENCE));
			var in = new DataInputStream(pair.receiver().socket.getInputStream());
			assertEquals(in.readInt(), payload.size());
			assertEquals(in.readNBytes(payload.size()), payload.toByteArray());
		}
	}
	
	@Test
	public void malformedFramesAreRejected() throws Exception{
		for(byte[] payload : List.of(new byte[0], new byte[]{99}, new byte[]{8, 0}, new byte[]{1},
		                             new byte[]{2, -1, -1, -1, -1}, new byte[]{6, 0, 0, 0, 32, 1})){
			try(var pair = Pair.open()){
				var out = new DataOutputStream(pair.sender().socket.getOutputStream());
				out.writeInt(payload.length);
				out.write(payload);
				out.flush();
				expectThrows(IOException.class, () -> pair.receiver().receive());
			}
		}
		
		for(int length : new int[]{-1, 16*1024*1024 + 1, 10}){
			try(var pair = Pair.open()){
				new DataOutputStream(pair.sender().socket.getOutputStream()).writeInt(length);
				pair.sender().socket.shutdownOutput();
				expectThrows(IOException.class, () -> pair.receiver().receive());
			}
		}
	}
	
	@Test
	public void oversizedPayloadDoesNotWriteAPartialFrame() throws Exception{
		try(var pair = Pair.open()){
			expectThrows(IOException.class, () -> pair.sender().send(new Wire.Output(false, new byte[8193])));
			expectThrows(IOException.class, () -> pair.sender().send(new Wire.Job(new byte[RecipeCodec.MAX_BYTES + 1])));
			
			pair.sender().send(new Wire.Ping());
			assertEquals(pair.receiver().receive(), new Wire.Ping());
		}
	}
	
	@Test(timeOut = 10000)
	public void concurrentSendersDoNotInterleaveFrames() throws Exception{
		try(var pair = Pair.open(); var pool = Executors.newVirtualThreadPerTaskExecutor()){
			var sends = new ArrayList<Future<?>>();
			for(int i = 0; i<100; i++){
				long id = i;
				sends.add(pool.submit(() -> {
					pair.sender().send(new Wire.Start(new Wire.Assignment(JOB, id)));
					return null;
				}));
			}
			
			var ids = new HashSet<Long>();
			for(int i = 0; i<100; i++){
				var start = (Wire.Start)pair.receiver().receive();
				assertEquals(start.assignment().job(), JOB);
				assertTrue(ids.add(start.assignment().id()));
			}
			
			for(var send : sends){ send.get(5, TimeUnit.SECONDS); }
		}
	}
}
