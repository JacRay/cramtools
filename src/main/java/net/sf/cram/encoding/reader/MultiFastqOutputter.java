package net.sf.cram.encoding.reader;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import net.sf.cram.ref.ReferenceSource;
import net.sf.picard.util.Log;
import net.sf.samtools.BAMFileWriter;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMSequenceRecord;

public class MultiFastqOutputter extends AbstractFastqReader {
	private static final Log log = Log.getInstance(MultiFastqOutputter.class);
	private Map<FastqRead, FastqRead> readSet = new TreeMap<FastqRead, FastqRead>();
	private int maxCacheSize = 100000;
	private long generation = 0;
	private OutputStream[] streams;

	private SAMFileHeader headerForOverflowWriter;
	private BAMFileWriter writer;
	private OutputStream cacheOverFlowStream;
	private byte[] prefix;
	private long counter = 1;
	private ReferenceSource referenceSource;
	private SAMFileHeader header;

	public MultiFastqOutputter(OutputStream[] streams,
			OutputStream cacheOverFlowStream, ReferenceSource referenceSource,
			SAMFileHeader header) {
		this.streams = streams;
		this.cacheOverFlowStream = cacheOverFlowStream;
		this.referenceSource = referenceSource;
		this.header = header;
	}

	public byte[] getPrefix() {
		return prefix;
	}

	public void setPrefix(byte[] prefix) {
		this.prefix = prefix;
	}

	public long getCounter() {
		return counter;
	}

	public void setCounter(long counter) {
		this.counter = counter;
	}

	protected void write(FastqRead read, OutputStream stream)
			throws IOException {
		if (prefix == null) {
			stream.write(read.data);
		} else {
			streams[read.templateIndex].write('@');
			streams[read.templateIndex].write(prefix);
			streams[read.templateIndex].write('.');
			streams[read.templateIndex].write(String.valueOf(counter)
					.getBytes());
			streams[read.templateIndex].write(' ');
			streams[read.templateIndex].write(read.data, 1,
					read.data.length - 1);
		}
	}

	protected void foundCollision(FastqRead read) {
		FastqRead anchor = readSet.remove(read);
		try {
			write(anchor, streams[anchor.templateIndex]);
			write(read, streams[read.templateIndex]);
			counter++;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Comparator<FastqRead> byGenerationComparator = new Comparator<FastqRead>() {

		@Override
		public int compare(FastqRead o1, FastqRead o2) {
			return (int) (o1.generation - o2.generation);
		}
	};

	protected void kickedFromCache(FastqRead read) {
		if (writer == null) {
			log.info("Creating overflow BAM file.");
			headerForOverflowWriter = new SAMFileHeader();
			headerForOverflowWriter
					.setSortOrder(SAMFileHeader.SortOrder.queryname);

			writer = new BAMFileWriter(cacheOverFlowStream, null);
			writer.setHeader(headerForOverflowWriter);
		}
		SAMRecord r = read.toSAMRecord(headerForOverflowWriter);
		writer.addAlignment(r);
	}

	List<FastqRead> list = new ArrayList<FastqRead>();

	protected void purgeCache() {
		long time1 = System.nanoTime();
		list.clear();
		for (FastqRead read : readSet.keySet())
			list.add(read);

		Collections.sort(list, byGenerationComparator);
		for (int i = 0; i < list.size() / 2; i++) {
			readSet.remove(list.get(i));
			kickedFromCache(list.get(i));
		}

		list.clear();
		long time2 = System.nanoTime();
		System.out.println(String.format("Cache purged in %.2fms.\n",
				(time2 - time1) / 1000000f));
	}

	@Override
	protected void writeRead(byte[] name, int flags, byte[] bases, byte[] scores) {
		FastqRead read = new FastqRead(readLength, name,
				appendSegmentIndexToReadNames,
				getSegmentIndexInTemplate(flags), bases, scores);
		read.generation = generation++;
		if (read.templateIndex == 0) {
			try {
				write(read, streams[0]);
				counter++;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			return;
		}

		if (readSet.containsKey(read)) {
			foundCollision(read);
		} else {
			readSet.put(read, read);

			if (readSet.size() > maxCacheSize)
				purgeCache();
		}
	}

	@Override
	public void finish() {
		for (FastqRead read : readSet.keySet())
			kickedFromCache(read);

		readSet.clear();
		if (writer != null)
			writer.close();
		writer = null;
	}

	@Override
	protected byte[] refSeqChanged(int seqID) {
		SAMSequenceRecord sequence = header.getSequence(seqID);
		return referenceSource.getReferenceBases(sequence, true);
	}
}