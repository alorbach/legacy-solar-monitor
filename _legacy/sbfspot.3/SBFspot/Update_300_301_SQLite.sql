DROP VIEW IF EXISTS vwMonthData;

CREATE View vwMonthData AS
	select date(Dat.TimeStamp, 'unixepoch') AS TimeStamp,
	Inv.Name, Inv.Type, Dat.Serial,
	TotalYield, DayYield FROM MonthData Dat
INNER JOIN Inverters Inv ON Dat.Serial=Inv.Serial
ORDER BY Dat.Timestamp Desc;
