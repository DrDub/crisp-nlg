package crisp.main;

import crisp.converter.CurrentNextCrispConverter;
import crisp.planningproblem.Domain;
import crisp.planningproblem.Problem;
import crisp.planningproblem.codec.PddlOutputCodec;
import de.saar.chorus.term.Compound;
import de.saar.chorus.term.Term;
import de.saar.penguin.tag.codec.CrispXmlOutputCodec;
import de.saar.penguin.tag.codec.ParserException;
import de.saar.penguin.tag.codec.xtag.ParseException;
import de.saar.penguin.tag.codec.xtag.XtagInputCodec;
import de.saar.penguin.tag.grammar.CrispGrammar;
import de.saar.penguin.tag.grammar.Grammar;
import de.saar.penguin.tag.grammar.LexiconEntry;
import de.saar.penguin.tag.grammar.converter.XtagToCrispConverter;
import de.saar.penguin.tag.grammar.filter.GrammarFilterer;
import de.saar.penguin.tag.grammar.filter.WordListFilter;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

public class XtagScalingExperiment {
	public static void main(String[] args) throws ParserException, IOException, ParseException, ParserConfigurationException, SAXException {
		System.err.println("Reading XTAG grammar ...");
		File xtagPath = new File(args[0]);
		Grammar<String> xtagGrammar = new Grammar<String>();
		new XtagInputCodec().parse(xtagPath, xtagGrammar);

		System.err.println("Converting into CRISP format ...");
		Grammar<Term> crispGrammar = new XtagToCrispConverter().convertXtagToCrisp(xtagGrammar);
		
		Set<String> allowedWords = new HashSet<String>();
		allowedWords.add("the");
		allowedWords.add("businessman");
		allowedWords.add("sneeze");
		allowedWords.add("admire");
		allowedWords.add("give");
		allowedWords.add("but");
		Grammar<Term> filteredGrammar = new GrammarFilterer<Term>().filter(crispGrammar, new WordListFilter(allowedWords, false));
		
		for( int k = 1; k <= 3; k++ ) {
			for( int n = 1; n <= 10; n++ ) {
				generateProblem(n,k,filteredGrammar);
			}
		}
	}
	
	
	
	private static void generateProblem(int n, int k, Grammar<Term> grammar) throws ParserConfigurationException, SAXException, IOException {
		System.err.print("Generating problem for n=" + n + ", k=" + k + " ... ");
		
		// compute modified grammar
		Map<String,Integer> multiplicity = new HashMap<String, Integer>();
		multiplicity.put("businessman", k*n);
		CrispGrammar mGrammar = multiplyLexiconEntries(multiplicity, grammar);

        FileWriter gf = new FileWriter(new File("xtag-grammar-" + n + "-" + k + ".xml"));
        new CrispXmlOutputCodec().write(mGrammar, gf);
        gf.flush();
        gf.close();

		// compute problem
		StringBuffer buf = new StringBuffer();
		int plansize = n*(k+1); //n*(2*k+2);
		
		buf.append("<crispproblem name='xtag' grammar='' cat='S' index='e1' plansize='" + plansize + "'>");
		for( int i = 1; i <= n; i++ ) {
			StringBuffer verbpred = new StringBuffer(getVerbPred(k) + "(e" + i);
			
			for( int j = 1; j <= k; j++ ) {
				String aij = "a" + i + "_" + j;
				int bmanIndex = j + k*(i-1);
				
				verbpred.append("," + aij);
				appendpred(buf, "the-1(" + aij + ")");
				appendpred(buf, "businessman-1" + bmanIndex + "(" + aij + ")");
			}
			
			verbpred.append(")");
			appendpred(buf, verbpred.toString());
			
			if( i < n ) {
				appendpred(buf, "but-2(e" + i + ",e" + (i+1) + ")");
			}
		}
		buf.append("</crispproblem>");
		
		FileWriter f = new FileWriter(new File("xtag-problem-" + n + "-" + k + ".xml"));
		f.write(buf.toString());
		f.flush();
		f.close();
		

        
		// generate planning problem		
		Domain domain = new Domain();
		Problem problem = new Problem();

		long start = System.currentTimeMillis();		
		new CurrentNextCrispConverter().convert(mGrammar, new StringReader(buf.toString()), domain, problem);
		long end = System.currentTimeMillis();
		
		System.err.println("done, " + (end-start) + "ms.");

		new PddlOutputCodec().writeToDisk(domain, problem, "./", "xtag-" + n + "-" + k);
		
	}



	private static void appendpred(StringBuffer buf, String string) {
		buf.append("<world>" + string + "</world>");
		buf.append("<commgoal>" + string + "</commgoal>");
	}



	private static String getVerbPred(int k) {
		switch(k) {
		case 1: return "sneeze-2";
		case 2: return "admire-3";
		case 3: return "give-4";
		}
		
		return null;
	}



	private static CrispGrammar multiplyLexiconEntries(Map<String,Integer> rules, Grammar<Term> grammar) {
        CrispGrammar ret = new CrispGrammar();
		
		for( String word : grammar.getAllWords() ) {
			for( LexiconEntry entry : grammar.getLexiconEntries(word)) {
				ret.addTree(entry.tree, grammar.getTree(entry.tree));

				if( rules.containsKey(word) ) {
					for( int i = 1; i <= rules.get(word); i++ ) {
						List<Term> newSemantics = new ArrayList<Term>(1);
						Compound oldSem = (Compound) entry.semantics.get(0);
						newSemantics.add(new Compound(oldSem.getLabel() + i, oldSem.getSubterms()));
						ret.addLexiconEntry(word + i, entry.tree, entry.auxLexicalItems, newSemantics);
					}
				} else {
					ret.addLexiconEntry(word, entry.tree, entry.auxLexicalItems, entry.semantics);
				}
			}
		}
		
		return ret;
	}
}