import java.util.PriorityQueue;

/**
 * Although this class has a history of several years,
 * it is starting from a blank-slate, new and clean implementation
 * as of Fall 2018.
 * <P>
 * Changes include relying solely on a tree for header information
 * and including debug and bits read/written information
 * 
 * @author Owen Astrachan
 */

public class HuffProcessor {

	public static final int BITS_PER_WORD = 8;
	public static final int BITS_PER_INT = 32;
	public static final int ALPH_SIZE = (1 << BITS_PER_WORD); 
	public static final int PSEUDO_EOF = ALPH_SIZE;
	public static final int HUFF_NUMBER = 0xface8200;
	public static final int HUFF_TREE  = HUFF_NUMBER | 1;

	private final int myDebugLevel;
	
	public static final int DEBUG_HIGH = 4;
	public static final int DEBUG_LOW = 1;
	
	public HuffProcessor() {
		this(0);
	}
	
	public HuffProcessor(int debug) {
		myDebugLevel = debug;
	}

	/**
	 * Compresses a file. Process must be reversible and loss-less.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be compressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void compress(BitInputStream in, BitOutputStream out){
		// Create counts array for each letter
		int[] counts = readForCounts(in);
		// Create the tree using the counts array
		HuffNode root = makeTreeFromCounts(counts);
		// Make the encoding String[] array from the tree
		String[] codings = makeCodingsFromTree(root);
		
		
		// Write out the HUFF number, as well the Huff header (containing the encoding)
		out.writeBits(BITS_PER_INT, HUFF_TREE);
		writeHeader(root,out);
		
		
		in.reset();
		// Write out all the bits read from in, compressed using String[] array 'codings'
		writeCompressedBits(codings,in,out);
		out.close();
	}

	private void writeCompressedBits(String[] encodings, BitInputStream in, BitOutputStream out) {
		// Keep reading, until loop broken when bit == -1, reached end
		while(true) {
			// Our 8-bit word read
			int bit = in.readBits(BITS_PER_WORD);
			if(bit == -1) {
				// Write pseudo_eof if bit == -1 and we reached the end
				String code = encodings[PSEUDO_EOF];
				out.writeBits(code.length(), Integer.parseInt(code,2));
				break;
			}
			// Else we will find the encoding for the bit and write it out
			String code = encodings[bit];
			out.writeBits(code.length(), Integer.parseInt(code,2));
		}
	}

	private void writeHeader(HuffNode root, BitOutputStream out) {
		// If we are at a root we need to write out a single 1 followed by the value
		if (root.myLeft == null && root.myRight == null) {
			out.writeBits(1, 1);
			out.writeBits(BITS_PER_WORD + 1, root.myValue);
		}
		// Or else we will carry out a pre-order traversal writing zeros and continuing to
		// left or right child
		if (root.myLeft != null) {
		    out.writeBits(1, 0);
		    writeHeader(root.myLeft, out);
		}
		if (root.myRight != null) {
		    out.writeBits(1, 0);
		    writeHeader(root.myRight, out);
		}
	}

	private String[] makeCodingsFromTree(HuffNode root) {
		// We will create an array of encodings long enough to contain all possible 256 chars
		// and PSEUDO_EOF, call coding helper to recursively create encodings
		String[] encodings = new String[ALPH_SIZE + 1];
		codingHelper(root,"",encodings);
		return encodings;
	}

	private void codingHelper(HuffNode root, String path, String[] encodings) {
		// If root is a leaf, we have found the path for the value stored in it, assign it to encodings
		if (root.myLeft == null && root.myRight == null) {
			encodings[root.myValue] = path;
			return;
		}
		// Else if we didn't return, we will traverse by pre-order to write into path either a 0 for left or 1 for right
		if (root.myLeft != null) {
			codingHelper(root.myLeft, path+"0", encodings);
		}
		if (root.myRight != null) {
			codingHelper(root.myRight, path+"1", encodings);
		}
	}

	private HuffNode makeTreeFromCounts(int[] counts) {
		
		PriorityQueue<HuffNode> pq = new PriorityQueue<>();

		// We'll add all HuffNodes containing the characters (indices of count) and their frequencies
		// (the values stored in count) to the priority queue, which will be automatically ordered by
		// frequency. Skip letters with frequency = 0
		for(int index = 0; index < counts.length; index++) {
			if(counts[index] == 0) continue;
			pq.add(new HuffNode(index,counts[index],null,null));
		}
		
		// We'll combine every two of the lightest nodes by their weight (frequency) into a single parent
		while (pq.size() > 1) {
			HuffNode left = pq.remove();
			HuffNode right = pq.remove();
			HuffNode t = new HuffNode(0,left.myWeight + right.myWeight,left,right);
			pq.add(t);
		}
		
		// The last element contained by the priority queue will be the weightiest, the root itself
		return pq.remove();
	}

	private int[] readForCounts(BitInputStream in) {
		int[] result = new int[ALPH_SIZE + 1];
		
		// We know there is one PSEUDO_EOF ending to the file 'in'
		result[PSEUDO_EOF] = 1;
		
		// Read 'in', until we reach the end of the file, count up the frequencies of letters
		// contained in 'bit' by adding one each time a particular 'bit' is encountered
		while(true) {
			int bit = in.readBits(BITS_PER_WORD);
			if(bit == -1) break;
			result[bit]++;
		}

		return result;
	}

	/**
	 * Decompresses a file. Output file must be identical bit-by-bit to the
	 * original.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be decompressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void decompress(BitInputStream in, BitOutputStream out){
		
		// Make sure our file is a huff-compressed file by reading its "magic" number
		int bits = in.readBits(BITS_PER_INT);
		if(bits != HUFF_TREE) {
			throw new HuffException("illegal header start with "+bits);
		}
		
		// Create our Huff-Tree using the header of the file
		HuffNode root = readTreeHeader(in);
		// Read the compressed bit of the file using this Huff-Tree
		readCompressedBits(root,in,out);
		out.close();
	}

	private void readCompressedBits(HuffNode root, BitInputStream in, BitOutputStream out) {
		HuffNode current = root;
		
		// This loop breaks once our file reading ends
		while (true) {
			int bits = in.readBits(1);
			// We will only reach a -1 if no PSEUDO_EOF was encoded, which should be an error
			if (bits == -1) {
				throw new HuffException("bad input, no PSEUDO_EOF");
			}
			else { 
				// 0 means left and 1 means right
				if (bits == 0) current = current.myLeft;
				else current = current.myRight;
				
				// If current node is a leaf, we will write out its usual bit-value, if it's not PSEUDO_EOF
				if (current.myLeft == null && current.myRight == null) {
					if (current.myValue == PSEUDO_EOF) 
						break;   // out of loop
					else {
						out.writeBits(BITS_PER_WORD, current.myValue);
						current = root; // start back after leaf
					}
				}
			}
		}
	}

	private HuffNode readTreeHeader(BitInputStream in) {
		// Read the next bit of 'in' in this recursive function
		int bit = in.readBits(1);
		// Necessary error-checking
		if (bit == -1) throw new HuffException("error in reading tree header");
		
		// If our bit is a zero, we're at an internal node and will continue pre-order traversal
		if (bit == 0) {
		    HuffNode left = readTreeHeader(in);
		    HuffNode right = readTreeHeader(in);
		    return new HuffNode(0,0,left,right);
		}
		// Else we will read the 1+8 -bits containing the leaf's value
		else {
		    int value = in.readBits(BITS_PER_WORD + 1);
		    return new HuffNode(value,0,null,null);
		}
	}
}