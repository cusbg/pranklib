package cz.siret.prank.lib;

import com.google.gson.Gson;

import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

import org.biojava.nbio.structure.Chain;
import org.biojava.nbio.structure.Group;
import org.biojava.nbio.structure.GroupType;
import org.biojava.nbio.structure.ResidueNumber;
import org.biojava.nbio.structure.Structure;
import org.biojava.nbio.structure.StructureException;
import org.biojava.nbio.structure.io.PDBFileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import cz.siret.prank.lib.utils.Tuple;
import cz.siret.prank.lib.utils.Tuple2;
import cz.siret.prank.lib.utils.Utils;

public class ConservationScore implements Serializable {
    private Map<ResidueNumberWrapper, Double> scores;
    private final transient Logger logger = LoggerFactory.getLogger(getClass());

    private ConservationScore(Map<ResidueNumberWrapper, Double> scores) {
        this.scores = scores;
    }

    public static List<Tuple2<File, String>> pickScoresForPDBs(File[] files)
            throws IOException, StructureException {
        PDBFileReader reader = new PDBFileReader();
        List<Tuple2<File, String>> scoreFiles = new ArrayList<>(files.length);
        for (File pdbFile : files) {
            try (InputStream pdbIn = new FileInputStream(pdbFile)) {
                Structure s = reader.getStructure(pdbIn);
                for (Chain chain : s.getChains()) {
                    // Skip non-protein chains.
                    if (chain.getAtomGroups(GroupType.AMINOACID).size() <= 0) continue;
                    // Try to find score for this chain.
                    String nameBase = pdbFile.getName()
                            .substring(0, pdbFile.getName().length() - 4);
                    Path parentDir = Paths.get(pdbFile.getParent());
                    String chainId = chain.getChainID().trim().isEmpty() ? "A" : chain.getChainID();
                    File scoreFile = parentDir.
                            resolve(nameBase + chainId.toUpperCase() + ".scores").toFile();
                    if (scoreFile.exists()) {
                        scoreFiles.add(Tuple.create(scoreFile, scoreFile.getName()));
                        continue;
                    }
                    // Fallback case. Try all chains and pick the one with longest LCS.
                    File[] possibleScoreFiles = parentDir.toFile().listFiles(
                            (File dir, String name) -> {
                                if (name.startsWith(nameBase) && name.endsWith(".scores")) {
                                    return true;
                                }
                                return false;
                            });
                    int max = -1;
                    File newScoreFile = null;
                    for (File possibleScoreFile : possibleScoreFiles) {
                        List<AA> scores = loadScoreFile(possibleScoreFile, ScoreFormat.JSDFormat);
                        int[][] lcs = calcLongestCommonSubSequence(
                                chain.getAtomGroups(GroupType.AMINOACID), scores);
                        int length = lcs[lcs.length - 1][lcs[lcs.length - 1].length - 1];
                        if (max < length) {
                            max = length;
                            newScoreFile = possibleScoreFile;
                        }
                    }
                    if (scoreFile != null) {
                        scoreFiles.add(Tuple.create(newScoreFile, scoreFile.getName()));
                    }
                }
            }
        }
        return scoreFiles;
    }

    private static class AA {
        public String letter;
        public double score;
        public int index;

        public AA(String letter, double score, int index) {
            this.letter = letter;
            this.score = score;
            this.index = index;
        }
    }

    public double getScoreForResidue(ResidueNumber residueNum) {
        return getScoreForResidue(new ResidueNumberWrapper(residueNum));
    }

    public double getScoreForResidue(ResidueNumberWrapper residueNum) {
        Double res = scores.get(residueNum);
        if (res == null) {
            return 0;
        } else {
            return res.doubleValue();
        }
    }

    public Map<ResidueNumberWrapper, Double> getScoreMap() {
        return scores;
    }

    public int size() {
        return this.scores.size();
    }

    public void toJson(File scoreFile) throws FileNotFoundException {
        Utils.stringToFile((new Gson()).toJson(this), scoreFile);
    }

    public static ConservationScore fromJson(File scoreFile) throws IOException {
        Gson gson = new Gson();
        try (BufferedReader reader = new BufferedReader(new FileReader(scoreFile))) {
            return gson.fromJson(reader, ConservationScore.class);
        }
    }

    public enum ScoreFormat {
        ConCavityFormat,
        JSDFormat
    }

    private static List<AA> loadScoreFile(File scoreFile, ScoreFormat format) {
        TsvParserSettings settings = new TsvParserSettings();
        settings.setLineSeparatorDetectionEnabled(true);
        TsvParser parser = new TsvParser(settings);
        List<String[]> lines = parser.parseAll(scoreFile);
        List<AA> result = new ArrayList<>(lines.size());
        for (String[] line : lines) {
            int index = -1;
            double score = 0;
            String letter = "-";
            switch (format) {
                case ConCavityFormat:
                    index = Integer.parseInt(line[0]);
                    letter = line[1];
                    score = Double.parseDouble(line[2]);
                    break;
                case JSDFormat:
                    index = Integer.parseInt(line[0]);
                    score = Double.parseDouble(line[1]);
                    letter = line[2].substring(0, 1);
                    break;
            }
            score = score < 0 ? 0 : score;
            if (letter != "-") {
                result.add(new AA(letter, score, index));
            }
        }
        return result;
    }

    public static ConservationScore fromFiles(Structure structure,
                                              Function<String, File> scoresFiles)
            throws FileNotFoundException {
        return fromFiles(structure, scoresFiles, ScoreFormat.JSDFormat);
    }

    /**
     * @param chain       Chain from PDB Structure
     * @param chainScores Parse conservation scores.
     * @param outResult   Add matched scores to map (residual number -> conservation score)
     */
    public static void matchSequences(List<Group> chain, List<AA> chainScores,
                                      Map<ResidueNumberWrapper, Double> outResult) {
        // Check if the strings match
        String pdbChain = chain.stream().map(ch -> ch.getChemComp().getOne_letter_code()
                .toUpperCase()).collect(Collectors.joining());
        String scoreChain = chainScores.stream().map(ch -> ch.letter.toUpperCase())
                .collect(Collectors.joining());
        if (pdbChain.equals(scoreChain)) {
            for (int i = 0; i < chainScores.size(); i++) {
                outResult.put(new ResidueNumberWrapper(chain.get(i).getResidueNumber()),
                        chainScores.get(i).score);
            }
            return;
        }

        System.out.println("Matching chains using LCS");
        int[][] lcs = calcLongestCommonSubSequence(chain, chainScores);


        // Backtrack the actual sequence.
        int i = chain.size(), j = chainScores.size();
        while (i > 0 && j > 0) {
            // Letters are equal.
            if (chain.get(i - 1).getChemComp().getOne_letter_code().toUpperCase().equals(
                    chainScores.get(j - 1).letter.toUpperCase())) {
                outResult.put(new ResidueNumberWrapper(chain.get(i - 1).getResidueNumber()),
                        chainScores.get(j - 1).score);
                i--;
                j--;
            } else {
                if (lcs[i][j - 1] > lcs[i - 1][j]) {
                    j--;
                } else {
                    i--;
                }
            }
        }
    }

    public static int[][] calcLongestCommonSubSequence(List<Group> chain, List<AA> chainScores) {
        // Implementation of Longest Common SubSequence
        // https://en.wikipedia.org/wiki/Longest_common_subsequence_problem
        int[][] lcs = new int[chain.size() + 1][chainScores.size() + 1];
        for (int i = 0; i <= chain.size(); i++) lcs[i][0] = 0;
        for (int j = 0; j <= chainScores.size(); j++) lcs[0][j] = 0;
        for (int i = 1; i <= chain.size(); i++) {
            for (int j = 1; j <= chainScores.size(); j++) {
                // Letters are equal.
                if (chain.get(i - 1).getChemComp().getOne_letter_code().toUpperCase().equals(
                        chainScores.get(j - 1).letter.toUpperCase())) {
                    lcs[i][j] = lcs[i - 1][j - 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i - 1][j], lcs[i][j - 1]);
                }
            }
        }
        return lcs;
    }

    /**
     * Parses conservation scores created from HSSP database and Jensen-Shannon divergence.
     *
     * @param structure  Protein BioJava structure
     * @param scoreFiles Map from chain ids to files
     * @param format     Score format (JSD or ConCavity), default: JSD
     * @return new instance of ConservationScore (map from residual numbers to conservation scores)
     */
    public static ConservationScore fromFiles(Structure structure,
                                              Function<String, File> scoreFiles,
                                              ScoreFormat format) throws FileNotFoundException {
        Map<ResidueNumberWrapper, Double> scores = new HashMap<>();
        for (Chain chain : structure.getChains()) {
            if (chain.getAtomGroups(GroupType.AMINOACID).size() <= 0) {
                continue;
            }
            String chainId = chain.getChainID();
            chainId = chainId.trim().isEmpty() ? "A" : chainId;
            List<AA> chainScores = null;
            File scoreFile = scoreFiles.apply(chainId);
            if (scoreFile.exists()) {
                chainScores = ConservationScore.loadScoreFile(scoreFile, format);
            }
            if (chainScores != null) {
                matchSequences(chain.getAtomGroups(GroupType.AMINOACID), chainScores, scores);
            }
        }
        return new ConservationScore(scores);
    }

}
