package com.lapissea.fuzz.distributed;

import com.lapissea.fuzz.FuzzSequence;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class Wire implements AutoCloseable{
	
	public static final int VERSION = 6;
	
	public static final int HEARTBEAT_SECONDS       = 10;
	public static final int SILENCE_TIMEOUT_SECONDS = 4*HEARTBEAT_SECONDS;
	
	private static final int HELLO = 1, JOB = 2, WORK = 3, START = 4, RESULT = 5, OUTPUT = 6;
	private static final int CANCEL = 7, PING = 8, START_ACK = 9, CANCEL_ASSIGNMENT = 10, PROGRESS = 11, DEMAND = 12, CLEANUP_COMPLETE = 13;
	
	private static final int MAX_FRAME  = 16*1024*1024;
	private static final int MAX_STRING = 8*1024*1024;
	public static final  int MAX_OUTPUT = 8192;
	
	public record Assignment(UUID job, long id){
		public String key(){ return job + "/" + id; }
	}
	
	public sealed interface Message{ }
	
	public record Hello(int version, String token, String name, int concurrency, String bundleId, int javaFeature) implements Message{ }
	
	public record Job(byte[] recipe) implements Message{ }
	
	public record Work(Assignment assignment, FuzzSequence sequence) implements Message{ }
	
	public record Start(Assignment assignment) implements Message{ }
	
	public record StartAck(Assignment assignment, boolean allowed) implements Message{ }
	
	public record Progress(Assignment assignment, RemoteSequenceResult.ExecutionStats stats) implements Message{ }
	
	public record Result(RemoteSequenceResult result) implements Message{ }
	
	public record Output(boolean error, byte[] bytes) implements Message{ }
	
	public record Cancel(UUID jobId) implements Message{
		public Cancel(){ this(null); }
	}
	
	public record CleanupComplete(UUID jobId, String failure) implements Message{ }
	
	public record Ping() implements Message{ }
	
	public record CancelAssignment(Assignment assignment) implements Message{ }
	
	public record Demand(UUID jobId, int idle, int awaitingStart) implements Message{ }
	
	public final  Socket           socket;
	private final DataInputStream  in;
	private final DataOutputStream out;
	public Wire(Socket socket) throws IOException{
		this.socket = socket;
		in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
		out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
	}
	
	public synchronized void send(Message message) throws IOException{
		var bytes = new ByteArrayOutputStream();
		var data  = new DataOutputStream(bytes);
		switch(message){
			case Hello m -> {
				data.writeByte(HELLO);
				data.writeInt(m.version());
				string(data, m.token());
				string(data, m.name());
				data.writeInt(m.concurrency());
				string(data, m.bundleId());
				data.writeInt(m.javaFeature());
			}
			case Job m -> {
				data.writeByte(JOB);
				bytes(data, m.recipe(), RecipeCodec.MAX_BYTES);
			}
			case Work m -> {
				data.writeByte(WORK);
				assignment(data, m.assignment);
				sequence(data, m.sequence());
			}
			case Start m -> {
				data.writeByte(START);
				assignment(data, m.assignment);
			}
			case StartAck m -> {
				data.writeByte(START_ACK);
				assignment(data, m.assignment);
				data.writeBoolean(m.allowed());
			}
			case Progress m -> {
				data.writeByte(PROGRESS);
				assignment(data, m.assignment);
				stats(data, m.stats());
			}
			case Result m -> {
				data.writeByte(RESULT);
				result(data, m.result());
			}
			case Output m -> {
				data.writeByte(OUTPUT);
				data.writeBoolean(m.error());
				bytes(data, m.bytes(), MAX_OUTPUT);
			}
			case Cancel m -> {
				data.writeByte(CANCEL);
				data.writeBoolean(m.jobId() != null);
				if(m.jobId() != null) string(data, m.jobId().toString());
			}
			case CleanupComplete m -> {
				data.writeByte(CLEANUP_COMPLETE);
				string(data, m.jobId().toString());
				data.writeBoolean(m.failure() != null);
				if(m.failure() != null) string(data, m.failure());
			}
			case Ping ignored -> data.writeByte(PING);
			case CancelAssignment m -> {
				data.writeByte(CANCEL_ASSIGNMENT);
				assignment(data, m.assignment);
			}
			case Demand m -> {
				data.writeByte(DEMAND);
				string(data, m.jobId().toString());
				data.writeInt(m.idle());
				data.writeInt(m.awaitingStart());
			}
		}
		data.flush();
		if(bytes.size()>MAX_FRAME){ throw new IOException("Frame too large"); }
		out.writeInt(bytes.size());
		bytes.writeTo(out);
		out.flush();
	}
	
	public Message receive() throws IOException{
		var data = new DataInputStream(new ByteArrayInputStream(bytes(in, MAX_FRAME)));
		try{
			Message message = switch(data.readUnsignedByte()){
				case HELLO -> new Hello(data.readInt(), string(data), string(data), data.readInt(), string(data), data.readInt());
				case JOB -> new Job(bytes(data, RecipeCodec.MAX_BYTES));
				case WORK -> new Work(assignment(data), sequence(data));
				case START -> new Start(assignment(data));
				case START_ACK -> new StartAck(assignment(data), data.readBoolean());
				case PROGRESS -> new Progress(assignment(data), stats(data));
				case RESULT -> new Result(result(data));
				case OUTPUT -> new Output(data.readBoolean(), bytes(data, MAX_OUTPUT));
				case CANCEL -> new Cancel(data.readBoolean()? uuid(data) : null);
				case CLEANUP_COMPLETE -> new CleanupComplete(uuid(data), data.readBoolean()? string(data) : null);
				case PING -> new Ping();
				case CANCEL_ASSIGNMENT -> new CancelAssignment(assignment(data));
				case DEMAND -> new Demand(uuid(data), data.readInt(), data.readInt());
				default -> throw new IOException("Unknown message type");
			};
			if(data.available() != 0){
				throw new IOException("Trailing message data");
			}
			return message;
		}catch(IllegalArgumentException e){
			throw new IOException("Invalid message payload", e);
		}
	}
	
	private static void assignment(DataOutputStream out, Assignment assignment) throws IOException{
		string(out, assignment.job.toString());
		out.writeLong(assignment.id);
	}
	private static Assignment assignment(DataInputStream data) throws IOException{
		return new Assignment(uuid(data), data.readLong());
	}
	private static UUID uuid(DataInputStream in) throws IOException{ return UUID.fromString(string(in)); }
	private static void bytes(DataOutputStream out, byte[] bytes, int max) throws IOException{
		if(bytes.length>max){
			throw new IOException("Payload too large");
		}
		out.writeInt(bytes.length);
		out.write(bytes);
	}
	private static byte[] bytes(DataInputStream in, int max) throws IOException{
		int length = in.readInt();
		if(length<0 || length>max){
			throw new IOException("Invalid payload size");
		}
		byte[] bytes = in.readNBytes(length);
		if(bytes.length != length){ throw new EOFException(); }
		return bytes;
	}
	private static void string(DataOutputStream out, String value) throws IOException{
		bytes(out, value.getBytes(StandardCharsets.UTF_8), MAX_STRING);
	}
	private static String string(DataInputStream in) throws IOException{
		return new String(bytes(in, MAX_STRING), StandardCharsets.UTF_8);
	}
	private static void sequence(DataOutputStream out, FuzzSequence s) throws IOException{
		out.writeLong(s.startIndex());
		out.writeLong(s.index());
		out.writeLong(s.seed());
		out.writeInt(s.iterations());
	}
	private static FuzzSequence sequence(DataInputStream in) throws IOException{
		return new FuzzSequence(in.readLong(), in.readLong(), in.readLong(), in.readInt());
	}
	private static void stats(DataOutputStream out, RemoteSequenceResult.ExecutionStats stats) throws IOException{
		out.writeLong(stats.completedOperations());
		out.writeLong(stats.executionNanos());
	}
	private static RemoteSequenceResult.ExecutionStats stats(DataInputStream in) throws IOException{
		return new RemoteSequenceResult.ExecutionStats(in.readLong(), in.readLong());
	}
	private static void result(DataOutputStream out, RemoteSequenceResult result) throws IOException{
		assignment(out, result.assignment());
		sequence(out, result.sequence());
		switch(result.status()){
			case RemoteSequenceResult.Ok ignored -> out.writeByte(0);
			case RemoteSequenceResult.Failed f -> {
				out.writeByte(1);
				var e = f.failure();
				out.writeLong(e.actionIndex());
				string(out, e.exceptionType());
				string(out, String.valueOf(e.message()));
				string(out, e.stackTrace());
			}
			case RemoteSequenceResult.Cancelled c -> {
				out.writeByte(2);
				string(out, c.reason());
			}
			case RemoteSequenceResult.InfrastructureError e -> {
				out.writeByte(3);
				string(out, e.message());
			}
		}
		stats(out, result.stats());
	}
	private static RemoteSequenceResult result(DataInputStream in) throws IOException{
		var assignment = assignment(in);
		var sequence   = sequence(in);
		RemoteSequenceResult.Status status = switch(in.readUnsignedByte()){
			case 0 -> new RemoteSequenceResult.Ok();
			case 1 -> new RemoteSequenceResult.Failed(new RemoteSequenceResult.RemoteFailure(in.readLong(), string(in), string(in), string(in)));
			case 2 -> new RemoteSequenceResult.Cancelled(string(in));
			case 3 -> new RemoteSequenceResult.InfrastructureError(string(in));
			default -> throw new IOException("Unknown result status");
		};
		return new RemoteSequenceResult(assignment, sequence, status, RemoteSequenceResult.CapturedOutput.EMPTY, stats(in));
	}
	
	@Override
	public void close() throws IOException{ socket.close(); }
}
