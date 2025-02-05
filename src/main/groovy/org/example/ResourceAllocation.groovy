package org.example

import com.niku.xmlserver.blob.NkCalendar
import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.core.NkDate
import com.niku.xmlserver.core.NkTime
import de.itdesign.clarity.logging.CommonLogger
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.time.TimeCategory
import groovy.transform.Field
import java.sql.Blob
import java.text.SimpleDateFormat

sql = new Sql(connection)

@Field CommonLogger cmnLog = new CommonLogger(this)
cmnLog.setFailJobOnError(true)

PROJECT = "5003001"
RESOURCE = "5004055"
FROM_DATE = Date.parse("yyyy-MM-dd", "2024-01-01")
TO_DATE = Date.parse("yyyy-MM-dd", "2026-01-01")
PERIOD = "MONTHLY"

// Main Execution Flow
def runExport() {
    cmnLog.info "Started exporting allocations at:-${new Date()}"
    try {
        assertParameters()
    } catch (Exception e) {
        cmnLog.error("Error occurred: ${e.message}", e)
        throw e  // Rethrow the exception to fail the job if required
    }
    cmnLog.info "Finished exporting allocations at:${new Date()}"
}

void assertParameters() {
    cmnLog.info "Parameters passed to the job: [Target CI Type: ${binding.variables.get('z_prj')}, Target PF Code: ${binding.variables.get('z_res')}, From: ${binding.variables.get('z_Strdate')}, To: ${binding.variables.get('z_endDate')}]"

    if (!binding.variables.containsKey("z_Strdate")) {
        def STRING_DATE = binding.variables.get('z_Strdate')
        cmnLog.info "${STRING_DATE}"
        def formattedString = STRING_DATE.replace("T", " ") // Replace T with space
        def date = Date.parse("yyyy-MM-dd HH:mm:ss", formattedString)
        FROM_DATE = date

    }

    if (binding.variables.containsKey("z_endDate")) {
        def STRING_DATES = binding.variables.get('z_endDate')
        cmnLog.info "${STRING_DATES}"
        def formattedStrings = STRING_DATES.replace("T", " ") // Replace T with space
        def dates = Date.parse("yyyy-MM-dd HH:mm:ss", formattedStrings)
        TO_DATE = dates
    }

    if (TO_DATE != null && FROM_DATE.after(TO_DATE)) {
        throw new Exception("The Date from when allocations to be read '${FROM_DATE}' lies after the Date until when allocations to be read '${TO_DATE}'")
    }

    PROJECT = binding.variables.get('z_prj')
    cmnLog.info "Project:-${PROJECT}"
    RESOURCE = binding.variables.get('z_res')
    cmnLog.info "Resource:-${RESOURCE}"
    PERIOD = binding.variables.get('z_period')
    cmnLog.info "Period:-${PERIOD}"
    generateAllocationCsv(PROJECT as String, RESOURCE as String, FROM_DATE, TO_DATE, PERIOD as String)
}

def generateAllocationCsv(String projectId, String resourceId, Date fromDate, Date toDate, String period) {
    GroovyRowResult investment = getProject(projectId)
    Integer periods = getNumberOfPeriods(fromDate, toDate, period)
    println "Total periods count: $periods"
    GroovyRowResult teamData = getResource(investment.id as String, resourceId)

    NkCurve softCurve = getCurveFromBlob(teamData, "pralloccurve")
    println"softcurve"
    println"${softCurve}"
    NkCurve hardCurve = getCurveFromBlob(teamData, "HARD_CURVE")

    if (softCurve) {
        NkCurve filteredSoftCurve = getFilterSegments(softCurve, fromDate, periods, period)
        println "Filtered Curve: ${filteredSoftCurve}"
        createCsvAndWriteToFile(filteredSoftCurve, investment, teamData, softCurve, hardCurve, fromDate, toDate, period)
    }

    if (hardCurve) {
        NkCurve filteredHardCurve = getFilterSegments(hardCurve, fromDate, periods, period)
        println "Filtered Curve: ${filteredHardCurve}"
        createCsvAndWriteToFile(filteredHardCurve, investment, teamData, softCurve, hardCurve, fromDate, toDate, period)
    }
}

def createCsvAndWriteToFile(NkCurve curve, GroovyRowResult investment, GroovyRowResult teamData, NkCurve softCurve, NkCurve hardCurve, Date fromDate, Date toDate, String period) {
    List<List<String>> csvData = []
    def periodIncrement, periodLength
    use(TimeCategory) {
        switch (period) {
            case "MONTHLY":
                periodIncrement = 1.month
                periodLength = 1.month - 1.day
                break
            case "QUARTERLY":
                periodIncrement = 3.month
                periodLength = 3.month - 1.day
                break
            case "YEARLY":
                periodIncrement = 1.year
                periodLength = 1.year - 1.day
                break
            default:
                throw new IllegalArgumentException("Unsupported period: $period")
        }
        def formatDate = new SimpleDateFormat("dd.MMM.yyyy")
        def currentStartDate = fromDate
        while (currentStartDate <= toDate) {
            def currentEndDate = currentStartDate + periodLength
            currentEndDate = currentEndDate > toDate ? toDate : currentEndDate
            def days = getPersonDays(curve, currentStartDate, currentEndDate)
            def formatStartDate = formatDate.format(currentStartDate)
            def formatEndDate = formatDate.format(currentEndDate)
            def row = [
                    investment.name,
                    teamData.PRRESOURCEID,
                    formatStartDate,
                    formatEndDate,
                    softCurve ? days : 0,
                    hardCurve ? days : 0
            ]
            csvData << row
            currentStartDate = currentStartDate + periodIncrement
        }
        writeToCSV(csvData, "resourceallocations.csv")
    }
}

def writeToCSV(List<List<String>> data, String outputFile) {
    println "Writing data to CSV file: $outputFile"
    File file = new File(outputFile)
    file.withWriter { writer ->
        writer.writeLine("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)")
        data.each { row ->
            writer.writeLine(row.join(","))
        }
    }
    println "CSV file has been written successfully."
}

GroovyRowResult getProject(String projectId) {
    def query = """SELECT id, code, name, schedule_start, schedule_finish FROM inv_investments WHERE id = ?"""
    return sql.firstRow(query, [projectId])
}

GroovyRowResult getResource(String projectId, String resourceId) {
    def query = """SELECT team.PRUID, team.prid, team.PRRESOURCEID, team.PRALLOCCURVE, team.HARD_CURVE 
                   FROM prteam team
                   INNER JOIN inv_investments ii ON ii.id = team.prprojectid
                   WHERE ii.id = ? AND team.PRRESOURCEID = ?"""
    return sql.firstRow(query, [projectId, resourceId])
}

NkCurve getCurveFromBlob(GroovyRowResult teamData, String curveName) {
    println "Converting Blob to NkCurve for curve: $curveName"
    Blob curveBlob = teamData?.get(curveName) as Blob

    if (!curveBlob) {
        println "Error: Curve Blob for $curveName is null."
        return null
    }
    byte[] curveBytes = curveBlob ? curveBlob.getBytes(1, (int) curveBlob.length()) : null
    NkCurve curve = curveBytes ? new NkCurve(curveBytes) : null
    if (!curve) {
        println "Error: Failed to create NkCurve from Blob for $curveName."
    }
    return curve
}

NkCurve getFilterSegments(NkCurve curve, Date start, Integer periods, String periodType) {
    NkCurve filteredCurve = new NkCurve(1)
    use(TimeCategory) {
        for (int i = 0; i < periods; i++) {
            def periodStartDate, periodEndDate
            switch (periodType) {
                case "MONTHLY":
                    periodStartDate = start + i.month
                    periodEndDate = start + (i + 1).month
                    break
                case "QUARTERLY":
                    periodStartDate = start + (i * 3).months
                    periodEndDate = start + ((i + 1) * 3).months
                    break
                case "YEARLY":
                    periodStartDate = start + (i * 12).months
                    periodEndDate = start + ((i + 1) * 12).months
                    break
            }
            def segExists = 0
            curve.segments.each { segment ->
                if (segment.startDate >= periodStartDate && segment.finishDate <= periodEndDate) {
                    segExists = 1
                    filteredCurve.segments.setSegment(segment)
                }
            }
            if (segExists == 0) {
                filteredCurve.segments.setSegment(NkTime.toNkTime(periodStartDate), NkTime.toNkTime(periodEndDate), 0.0D, null)
            }
        }
    }
    return filteredCurve
}

Integer getNumberOfPeriods(Date startDate, Date endDate, String period) {
    Calendar startCal = Calendar.getInstance()
    Calendar endCal = Calendar.getInstance()
    startCal.setTime(startDate)
    endCal.setTime(endDate)

    int yearsDiff = endCal.get(Calendar.YEAR) - startCal.get(Calendar.YEAR)
    int monthsDiff = endCal.get(Calendar.MONTH) - startCal.get(Calendar.MONTH)
    if (monthsDiff < 0) { yearsDiff--; monthsDiff += 12 }

    int totalMonthsDiff = (yearsDiff * 12) + monthsDiff
    switch (period) {
        case "MONTHLY": return totalMonthsDiff
        case "QUARTERLY": return Math.ceil(totalMonthsDiff / 3.0) as Integer
        case "YEARLY": return yearsDiff + (monthsDiff > 0 ? 1 : 0)
        default: throw new Exception("Invalid period type: $period")
    }
}

def getPersonDays(NkCurve curve, Date fromDate, Date toDate) {
    NkCalendar calendar = new NkCalendar()
    def days = 0
    def nkPeriodStartDate = NkTime.toNkTime(fromDate)
    def rate = curve.segments.getRate(nkPeriodStartDate)
    def startDate = new NkDate(fromDate, false)
    def endDate = new NkDate(toDate, false)
    def diffWorkingDays = calendar.diffWorkday(startDate, endDate)
    return (rate * diffWorkingDays).round(2)
}
runExport()
