// =========================================================================
// EXCELLON PARSER APP by N. De Santis, Unimercatorum, Matricola 0082300929
// =========================================================================

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// =========================================================================
// 1. GERARCHIA DELLE ECCEZIONI PERSONALIZZATE (CUSTOM EXCEPTIONS)
// =========================================================================

/**
 * Eccezione base del dominio di parsing Excellon.
 */
class ExcellonParseException extends Exception {
    public ExcellonParseException(String message) {
        super(message);
    }
}

/**
 * Eccezione sollevata in caso di intestazione (Header) malformata o non valida.
 */
class InvalidHeaderException extends ExcellonParseException {
    public InvalidHeaderException(String message) {
        super(message);
    }
}

/**
 * Eccezione sollevata quando si tenta di utilizzare un utensile non dichiarato nell'header.
 */
class UndeclaredToolException extends ExcellonParseException {
    private final String toolId;

    public UndeclaredToolException(String toolId) {
        super("Utensile non dichiarato nell'header: " + toolId);
        this.toolId = toolId;
    }

    public String getToolId() { 
        return toolId; 
    }
}

/**
 * Eccezione sollevata in presenza di coordinate X/Y malformate o non decodificabili.
 */
class MalformedCoordinateException extends ExcellonParseException {
    public MalformedCoordinateException(String message) {
        super(message);
    }
}

// =========================================================================
// 2. MODELLI DI DOMINIO E CLASSI DI SUPPORTO (DOMAIN MODELS)
// =========================================================================

/**
 * Rappresenta un utensile di foratura definito nel programma Excellon.
 */
class Tool {
    private final String id;        
    private final double diameter;  
    private int hitCount = 0;       
    private double totalDistance = 0.0; // Distanza percorsa dall'utensile

    public Tool(String id, double diameter) {
        this.id = id;
        this.diameter = diameter;
    }

    public String getId() { 
        return id; 
    }

    public double getDiameter() { 
        return diameter; 
    }

    public int getHitCount() { 
        return hitCount; 
    }

    public void incrementHitCount() { 
        this.hitCount++; 
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    public void addDistance(double distance) {
        this.totalDistance += distance;
    }
}

/**
 * Modulo di calcolo della distanza euclidea tra coordinate sequenziali.
 */
class DistanceCalculator {
    private double currentX = 0.0;
    private double currentY = 0.0;
    private double totalDistance = 0.0;
    private boolean isFirstPoint = true; // Flag per stabilire le coordinate del punto iniziale

    /**
     * Aggiorna la posizione della testa cartesiana ed accumula lo spostamento euclideo.
     *
     * @param newX Nuova coordinata X (null se inalterata rispetto al punto precedente)
     * @param newY Nuova coordinata Y (null se inalterata rispetto al punto precedente)
     * @return La distanza calcolata per il singolo segmento
     */
    public double addPoint(Double newX, Double newY) {
        // Gestione MODAL: se l'asse X o Y è assente (null), mantiene la coordinata corrente
        double targetX = (newX != null) ? newX : this.currentX;
        double targetY = (newY != null) ? newY : this.currentY;

        double segmentDistance = 0.0;

        if (isFirstPoint) {
            // Il primo punto definisce l'origine operativa della testa
            this.currentX = targetX;
            this.currentY = targetY;
            this.isFirstPoint = false;
        } else {
            // Calcolo della distanza Euclidea: sqrt((x2-x1)^2 + (y2-y1)^2)
            double deltaX = targetX - this.currentX;
            double deltaY = targetY - this.currentY;
            segmentDistance = Math.hypot(deltaX, deltaY);

            // Accumulo della distanza globale
            this.totalDistance += segmentDistance;
            
            // Aggiornamento dello stato della testa
            this.currentX = targetX;
            this.currentY = targetY;
        }

        return segmentDistance; 
    }

    public double getTotalDistance() {
        return totalDistance;
    }

    /**
     * Resetta il flag del primo punto per evitare di contare gli spostamenti
     * di riposizionamento a vuoto tra utensili diversi.
     */
    public void resetPoint() {
        this.isFirstPoint = true;
    }
}

/**
 * Modello dati per la raccolta delle metriche aggregate e della diagnostica di parsing.
 */
class DrillStatistics {
    private String fileName;
    private String unit = "INCH";                             
    private String zeroSuppression = "TZ";                    
    private final Map<String, Tool> toolTable = new TreeMap<>(); 
    private int totalHoles = 0;                              
    private double totalDistance = 0.0;                       
    private final List<String> warnings = new ArrayList<>(); 
    private final Set<String> reportedMissingTools = new HashSet<>(); 

    public void setFileName(String fileName) { 
        this.fileName = fileName; 
    }

    public void setUnit(String unit) { 
        this.unit = unit; 
    }

    public void setZeroSuppression(String zeroSuppression) { 
        this.zeroSuppression = zeroSuppression; 
    }

    public Map<String, Tool> getToolTable() { 
        return toolTable; 
    }

    public void addWarning(String warning) { 
        warnings.add(warning); 
    }

    public void addUndeclaredToolWarning(String toolId, int lineNumber) {
        if (reportedMissingTools.add(toolId)) {
            warnings.add("Riga " + lineNumber + ": Utensile " + toolId + " richiamato ma non dichiarato nell'header. Le coordinate successive verranno ignorate.");
        }
    }

    public void incrementHoles() { 
        totalHoles++; 
    }

    public void setTotalDistance(double dist) { 
        this.totalDistance = dist; 
    }

    public void printReport() {
        System.out.println(getReportAsString());
    }

    /**
     * Restituisce l'intero report formattato, includendo l'intestazione con i dati 
     * anagrafici, la data/ora, i dettagli utensili tabellati, le metriche e tutti i warning registrati.
     */
    public String getReportAsString() {
        StringBuilder sb = new StringBuilder();
        String unitLabel = switch (unit.toUpperCase()) {
            case "METRIC" -> "mm";
            case "INCH"   -> "in";
            default       -> unit;
        };
        sb.append("   ====================================================\n");
        sb.append("   EXCELLON PARSER APP - N. DE SANTIS - M. 082300929 \n");
        sb.append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("   dd/MM/yyyy, HH:mm"))).append("\n");
        sb.append("                                                  \n");
        sb.append("   REPORT ANALISI FILE EXCELLON          \n");
        sb.append("   ====================================================\n");
        sb.append("   File analizzato     : ").append(fileName).append("\n");
        sb.append("   Unità di misura     : ").append("METRIC".equalsIgnoreCase(unit) ? "millimetri" : "pollici").append("   \n");
        sb.append("   Soppressione Zeri   : ").append(zeroSuppression).append("\n");
        sb.append("   ----------------------------------------------------\n");
        sb.append("   TABELLA UTENSILI E CONTEGGIO COLPI:\n");
        
        // Intestazione delle colonne per la tabella utensili
        sb.append(String.format("   %-10s | %-10s | %-8s | %-14s%n", "Utensile", "Diametro", "Fori", "Percorso (" + unitLabel + ")"));
        sb.append("   -----------+------------+----------+----------------\n");

        // Calcolo della somma totale dei percorsi singoli per la precisione globale
        double sumToolsDistance = 0.0;
        for (Tool tool : toolTable.values()) {
            sumToolsDistance += tool.getTotalDistance();
            sb.append(String.format("   %-10s | %-10.4f | %-8d | %-14.4f%n",
                    tool.getId(), tool.getDiameter(), tool.getHitCount(), tool.getTotalDistance()));
        }
        sb.append("   ----------------------------------------------------\n");
        sb.append("   METRICHE GEOMETRICHE GLOBALI:\n");
        sb.append("   Totale Fori Eseguiti : ").append(totalHoles).append("\n");
        sb.append(String.format("   Percorso Totale Testa: %.4f %s%n", sumToolsDistance, unitLabel));

        if (unit.equalsIgnoreCase("INCH")) {
            sb.append(String.format("   (pari a circa        : %.2f mm / %.2f metri)%n", 
                    sumToolsDistance * 25.4, (sumToolsDistance * 25.4) / 1000.0));
        }

        if (!warnings.isEmpty()) {
            sb.append("   ----------------------------------------------------\n");
            sb.append("   REGISTRO AVVISI E DIAGNOSTICA (WARNINGS):\n");
            for (String w : warnings) {
                sb.append("     [!] ").append(w).append("\n");
            }
        }
        sb.append("   ====================================================");
        return sb.toString();
    }
}

// =========================================================================
// 3. CORE PARSER EXCELLON (CON GESTIONE INTERATTIVA DELLE RICHIESTE UTENTE)
// =========================================================================

public class ExcellonParserApp {

    private enum State { HEADER, BODY }

    // Pattern Regex per la dichiarazione degli utensili nell'header (T01C0.012F...B...S...H...)
    private static final Pattern TOOL_HEADER_PATTERN = 
            Pattern.compile("^T(\\d{1,2})C([\\d\\.]+)(?:F(\\d+))?(?:B(\\d+))?(?:S(\\d+))?(?:H(\\d+))?$", Pattern.CASE_INSENSITIVE);

    // Pattern Regex per l'unità di misura e soppressione zeri (INCH,TZ o METRIC,LZ)
    private static final Pattern CONFIG_PATTERN = 
            Pattern.compile("^(INCH|METRIC)(?:,(LZ|TZ))?$", Pattern.CASE_INSENSITIVE);

    // Pattern Regex per la selezione dell'utensile attivo nel Body (es. T01)
    private static final Pattern TOOL_SELECT_PATTERN = 
            Pattern.compile("^T(\\d{1,2})$", Pattern.CASE_INSENSITIVE);

    // Pattern Regex per le coordinate cartesiane X/Y
    private static final Pattern COORD_PATTERN = 
            Pattern.compile("^(?:X(-?\\d+))?(?:Y(-?\\d+))?$", Pattern.CASE_INSENSITIVE);

    private final DrillStatistics stats = new DrillStatistics();
    private final DistanceCalculator distanceCalculator = new DistanceCalculator();
    private Tool activeTool = null;       
    private String unit = "INCH";          
    private String zeroSuppression = "TZ"; 

    private boolean configFound = false;          
    private boolean zeroSuppressionFound = false; 
    private boolean m30Found = false;             

    // Interfaccia opzionale per gestire i prompt utente via GUI o Console
    public interface UserInteractionHandler {
        boolean askConfirmation(String title, String message);
    }

    private UserInteractionHandler interactionHandler;

    public void setUserInteractionHandler(UserInteractionHandler handler) {
        this.interactionHandler = handler;
    }

    public DrillStatistics parseFile(Path filePath) throws IOException, InvalidHeaderException {
        stats.setFileName(filePath.getFileName().toString());

        // Carica le righe non vuote per rilevare se M30 si trova a fine file o nel mezzo
        List<String> rawLines = Files.readAllLines(filePath);
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            String trimmed = l.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }

        int totalLines = lines.size();
        State currentState = State.HEADER;
        boolean headerFound = false;

        for (int i = 0; i < totalLines; i++) {
            String line = lines.get(i);
            int lineNumber = i + 1; // Numero riga per il report

            // 1. Verifica la presenza dell'identificatore d'inizio Header "M48"
            if (!headerFound) {
                if (line.equalsIgnoreCase("M48")) {
                    headerFound = true;
                    continue; 
                } else {
                    // Richiesta interattiva in caso di assenza dell'M48
                    boolean proceed = askUserConfirmation(
                        "Avviso: Identificatore 'M48' assente",
                        "Identificatore d'inizio header 'M48' assente!\n" +
                        "Trovata istruzione alla riga " + lineNumber + ": " + line + "\n\n" +
                        "Vuoi proseguire ugualmente con l'analisi dell'header?"
                    );

                    if (proceed) {
                        stats.addWarning("Identificatore 'M48' assente all'inizio del file.");
                        headerFound = true; 
                    } else {
                        throw new InvalidHeaderException("Parsing interrotto dall'utente per assenza dell'identificatore 'M48'.");
                    }
                }
            }

            // 2. Transizione da HEADER a BODY mediante il delimitatore '%'
            if (line.equals("%")) {
                if (!configFound || !zeroSuppressionFound) {
                    throw new InvalidHeaderException("Intestazione non valida: dichiarazione unità di misura (INCH/METRIC) e/o soppressione zeri (LZ/TZ) assente");
                }
                currentState = State.BODY;
                continue;
            }

            // 3. Fallback: Rilevazione passaggio implicito al BODY per omissione del carattere '%'
            if (currentState == State.HEADER && (TOOL_SELECT_PATTERN.matcher(line).matches() || COORD_PATTERN.matcher(line).matches())) {
                boolean proceed = askUserConfirmation(
                    "Avviso: Carattere '%' assente",
                    "Carattere di separazione '%' assente nell'header!\n" +
                    "Rilevata istruzione operativa alla riga " + lineNumber + ": " + line + "\n\n" +
                    "Vuoi continuare ugualmente impostando lo stato su BODY?"
                );

                if (proceed) {
                    if (!configFound || !zeroSuppressionFound) {
                        throw new InvalidHeaderException("Intestazione non valida: dichiarazione unità di misura (INCH/METRIC) e/o soppressione zeri (LZ/TZ) assente");
                    }
                    
                    stats.addWarning("Mancanza del carattere di separazione '%' tra Header e Body.");
                    currentState = State.BODY; 
                } else {
                    throw new InvalidHeaderException("Parsing interrotto dall'utente per assenza del delimitatore '%'.");
                }
            }

            if (currentState == State.HEADER) {
                parseHeaderLine(line, lineNumber);
            } else {
                boolean isLastLine = (i == totalLines - 1);
                boolean shouldContinue = parseBodyLine(line, lineNumber, isLastLine);
                if (!shouldContinue) {
                    // L'utente ha scelto di interrompere al riscontro di M30 prima della fine file
                    break;
                }
            }
        }

        if (!m30Found) {
            stats.addWarning("Comando di fine programma M30 assente a fine file.");
        }

        // Verifica utensili dichiarati nell'header ma mai utilizzati
        for (Tool tool : stats.getToolTable().values()) {
            if (tool.getHitCount() == 0) {
                stats.addWarning("Utensile " + tool.getId() + " (diametro " + tool.getDiameter() + ") dichiarato nell'header ma mai utilizzato nel body.");
            }
        }

        stats.setTotalDistance(distanceCalculator.getTotalDistance());
        return stats;
    }

    private boolean askUserConfirmation(String title, String message) {
        if (interactionHandler != null) {
            return interactionHandler.askConfirmation(title, message);
        } else {
            // Fallback console originario se eseguito via CLI
            System.out.println("--------------------------------------------------");
            System.out.println("AVVISO: " + message);
            System.out.print("Vuoi proseguire? (s/n): ");
            try (java.util.Scanner scanner = new java.util.Scanner(System.in)) {
                String answer = scanner.nextLine().trim();
                return answer.equalsIgnoreCase("s") || answer.equalsIgnoreCase("si");
            } catch (Exception e) {
                return false;
            }
        }
    }

    private void parseHeaderLine(String line, int lineNumber) {
        Matcher configMatcher = CONFIG_PATTERN.matcher(line);
        if (configMatcher.matches()) {
            this.unit = configMatcher.group(1).toUpperCase();
            stats.setUnit(this.unit);
            this.configFound = true; 

            if (configMatcher.group(2) != null) {
                this.zeroSuppression = configMatcher.group(2).toUpperCase();
                stats.setZeroSuppression(this.zeroSuppression);
                this.zeroSuppressionFound = true; 
            }
            return;
        }

        Matcher toolMatcher = TOOL_HEADER_PATTERN.matcher(line);
        if (toolMatcher.matches()) {
            String toolId = "T" + String.format("%02d", Integer.parseInt(toolMatcher.group(1)));
            double diameter = Double.parseDouble(toolMatcher.group(2));

            Tool tool = new Tool(toolId, diameter);
            stats.getToolTable().put(toolId, tool);
        }
    }

    private boolean parseBodyLine(String line, int lineNumber, boolean isLastLine) {
        if (line.equalsIgnoreCase("M30")) {
            this.m30Found = true;
            if (!isLastLine) {
                boolean proceed = askUserConfirmation(
                    "Avviso: Comando M30 in mezzo al file",
                    "Rilevato comando di fine programma M30 alla riga " + lineNumber + " (non al termine del file).\n\n" +
                    "Vuoi ignorare l'M30 e proseguire con le righe successive?"
                );

                if (proceed) {
                    stats.addWarning("Riga " + lineNumber + ": Comando M30 rilevato prima della fine del file (ignorato su richiesta dell'utente).");
                    return true;
                } else {
                    stats.addWarning("Riga " + lineNumber + ": Parsing interrotto dall'utente al riscontro del comando M30.");
                    return false; // Interrompe l'analisi del body
                }
            }
            return true;
        }

        Matcher toolSelMatcher = TOOL_SELECT_PATTERN.matcher(line);
        if (toolSelMatcher.matches()) {
            String toolId = "T" + String.format("%02d", Integer.parseInt(toolSelMatcher.group(1)));
            if (stats.getToolTable().containsKey(toolId)) {
                activeTool = stats.getToolTable().get(toolId); 
                // Reset del calcolatore di distanza per evitare di contare lo spostamento dal tool precedente
                distanceCalculator.resetPoint();
            } else {
                stats.addUndeclaredToolWarning(toolId, lineNumber);
                activeTool = null; 
            }
            return true;
        }

        Matcher coordMatcher = COORD_PATTERN.matcher(line);
        if (coordMatcher.matches() && (coordMatcher.group(1) != null || coordMatcher.group(2) != null)) {
            if (activeTool == null) {
                return true; // Ignora le coordinate per utensili non dichiarati
            }

            try {
                Double x = parseCoordinate(coordMatcher.group(1));
                Double y = parseCoordinate(coordMatcher.group(2));

                activeTool.incrementHitCount();
                stats.incrementHoles();
                
                // Calcola il segmento per il tool attivo e resetta lo spostamento tra cambi tool
                double segmentDistance = distanceCalculator.addPoint(x, y);
                activeTool.addDistance(segmentDistance);

            } catch (Exception e) {
                stats.addWarning("Riga " + lineNumber + ": " + e.getMessage());
            }
        } else {
            stats.addWarning("Riga " + lineNumber + ": Istruzione non riconosciuta -> " + line);
        }
        return true;
    }

    private Double parseCoordinate(String rawCoord) {
        if (rawCoord == null || rawCoord.isEmpty()) return null;

        boolean isNegative = rawCoord.startsWith("-");
        String digits = isNegative ? rawCoord.substring(1) : rawCoord;

        // Definizione cifre totali e decimali in base all'unità (2.4 per INCH, 3.3 per METRIC)
        int targetLength = 6;
        int decimals = "METRIC".equalsIgnoreCase(unit) ? 3 : 4;

        // Aggiunta zeri mancanti
        if ("LZ".equalsIgnoreCase(zeroSuppression)) {
            // Leading Zero: zeri omessi in testa -> pad a sinistra
            while (digits.length() < targetLength) {
                digits = "0" + digits;
            }
        } else {
            // Trailing Zero (TZ) o predefinito: zeri omessi in coda -> pad a destra
            while (digits.length() < targetLength) {
                digits = digits + "0";
            }
        }

        // Estrazione parte intera e decimale
        String integerPart = digits.substring(0, digits.length() - decimals);
        String decimalPart = digits.substring(digits.length() - decimals);

        double value = Double.parseDouble(integerPart + "." + decimalPart);
        return isNegative ? -value : value;
    }

    public static void main(String[] args) {
        if (args.length >= 1) {
            Path path = Paths.get(args[0]);
            ExcellonParserApp parser = new ExcellonParserApp();
            try {
                DrillStatistics result = parser.parseFile(path);
                result.printReport();
            } catch (Exception e) {
                System.err.println("ERRORE: " + e.getMessage());
            }
        } else {
            ExcellonGui.lanciaInterfaccia();
        }
    }
}

// =========================================================================
// 4. MODULO INTERFACCIA GRAFICA SEPARATO (GUI WRAPPER INTERATTIVO)
// =========================================================================

class ExcellonGui extends JFrame {
    private JTextArea reportArea;
    private JLabel statusLabel;

    public ExcellonGui() {
        setTitle("Excellon Parser - Interfaccia Grafica Completa");
        setSize(850, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel topPanel = new JPanel();
        JButton loadButton = new JButton("Seleziona e Analizza File Excellon (.drl / .txt)");
        loadButton.setFont(new Font("Arial", Font.BOLD, 14));
        topPanel.add(loadButton);
        add(topPanel, BorderLayout.NORTH);

        reportArea = new JTextArea();
        reportArea.setEditable(false);
        reportArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        add(new JScrollPane(reportArea), BorderLayout.CENTER);

        statusLabel = new JLabel(" Pronto. Selezionare un file da analizzare.", JLabel.LEFT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusLabel, BorderLayout.SOUTH);

        loadButton.addActionListener(e -> chooseAndParseFile());
    }

    private void chooseAndParseFile() {
        JFileChooser fileChooser = new JFileChooser();
        int result = fileChooser.showOpenDialog(this);

        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();
            Path path = selectedFile.toPath();
            
            ExcellonParserApp parser = new ExcellonParserApp();
            
            // Collega i prompt grafici interattivi (s/n) esattamente come previsti originariamente
            parser.setUserInteractionHandler((title, message) -> {
                int option = JOptionPane.showConfirmDialog(
                    this,
                    message,
                    title,
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );
                return option == JOptionPane.YES_OPTION;
            });

            try {
                DrillStatistics stats = parser.parseFile(path);
                reportArea.setText(stats.getReportAsString());
                statusLabel.setText(" Analisi completata con successo per: " + selectedFile.getName());
                
            } catch (Exception ex) {
                reportArea.setText("ERRORE DURANTE L'ANALISI DEL FILE:\n" + ex.getMessage());
                statusLabel.setText(" Errore rilevato durante l'analisi.");
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Errore di Parsing", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public static void lanciaInterfaccia() {
        SwingUtilities.invokeLater(() -> {
            new ExcellonGui().setVisible(true);
        });
    }
}