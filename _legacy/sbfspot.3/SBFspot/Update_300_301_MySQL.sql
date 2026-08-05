DROP VIEW IF EXISTS vwMonthData;

CREATE View vwMonthData AS
	convert_tz(from_unixtime(Dat.TimeStamp), 'SYSTEM', '+00:00') AS TimeStamp,
	Inv.Name, Inv.Type, Dat.Serial,
	TotalYield, DayYield FROM MonthData Dat
INNER JOIN Inverters Inv ON Dat.Serial=Inv.Serial
ORDER BY Dat.Timestamp Desc;