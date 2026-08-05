DROP VIEW IF EXISTS vwSpotData;

CREATE View vwSpotData AS
SELECT datetime(Dat.TimeStamp, 'unixepoch', 'localtime') TimeStamp, 
	datetime(CASE WHEN (Dat.TimeStamp % 300) < 150
	THEN Dat.TimeStamp - (Dat.TimeStamp % 300)
	ELSE Dat.TimeStamp - (Dat.TimeStamp % 300) + 300
	END, 'unixepoch', 'localtime') AS Nearest5min,
	Inv.Name,
	Inv.Type,
	Dat.Serial,
	Pdc1,Pdc2,
	Idc1,Idc2,
	Udc1,Udc2,
	Pac1,Pac2,Pac3,
	Iac1,Iac2,Iac3,
	Uac1,Uac2,Uac3,
	Pdc1+Pdc2 AS PdcTot,
	Pac1+Pac2+Pac3 AS PacTot,
	CASE WHEN Pdc1+Pdc2 = 0 THEN
	    0
	ELSE
	    CASE WHEN Pdc1+Pdc2>Pac1+Pac2+Pac3 THEN
	        ROUND(1.0*(Pac1+Pac2+Pac3)/(Pdc1+Pdc2)*100,1)
	    ELSE
	        100.0
	    END
	END AS Efficiency,
	Dat.EToday,
	Dat.ETotal,
	Frequency,
	Dat.OperatingTime,
	Dat.FeedInTime,
	ROUND(BT_Signal,1) AS BT_Signal,
	Dat.Status,
	Dat.GridRelay,
	ROUND(Dat.Temperature,1) AS Temperature
	FROM [SpotData] Dat
INNER JOIN Inverters Inv ON Dat.Serial = Inv.Serial
ORDER BY Dat.Timestamp Desc;
