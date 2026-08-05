######################################
# Makefile for SBFspot
#
# Compilation/Installation:
#	SQLite:	sudo make install_sqlite
# -or-
#	MySQL:	sudo make install_mysql
#
# Compilation Only:
#	make release_sqlite
# -or-
#	make release_mysql
#
# Installation Only:
#	sudo make install_release_sqlite
# -or-
#	sudo make install_release_mysql
######################################

SBFSPOTDIR = /usr/local/bin/sbfspot.3

CC = gcc
CXX = g++
AR = ar
LD = g++
WINDRES = windres

INC = 
CFLAGS = -Wall
RESINC = 
LIBDIR = 
LIB = -lbluetooth -lboost_date_time -lboost_system -lpthread
LDFLAGS = 

INC_RELEASE = $(INC)
CFLAGS_RELEASE = $(CFLAGS) -O2
RESINC_RELEASE = $(RESINC)
RCFLAGS_RELEASE = $(RCFLAGS)
LIBDIR_RELEASE = $(LIBDIR)
LIB_RELEASE = $(LIB)
LDFLAGS_RELEASE = $(LDFLAGS) -s
OBJDIR_RELEASE = obj/Release
DEP_RELEASE = 
OUT_RELEASE = bin/Release/SBFspot

INC_RELEASE_SQLITE = $(INC)
CFLAGS_RELEASE_SQLITE = $(CFLAGS) -O2 -DUSE_SQLITE
RESINC_RELEASE_SQLITE = $(RESINC)
RCFLAGS_RELEASE_SQLITE = $(RCFLAGS)
LIBDIR_RELEASE_SQLITE = $(LIBDIR)
LIB_RELEASE_SQLITE = $(LIB) -lsqlite3
LDFLAGS_RELEASE_SQLITE = $(LDFLAGS) -s
OBJDIR_RELEASE_SQLITE = obj/Release_SQLite
DEP_RELEASE_SQLITE = 
OUT_RELEASE_SQLITE = bin/Release_SQLite/SBFspot

INC_RELEASE_MYSQL = $(INC)
CFLAGS_RELEASE_MYSQL = $(CFLAGS) -O2 -DUSE_MYSQL
RESINC_RELEASE_MYSQL = $(RESINC)
RCFLAGS_RELEASE_MYSQL = $(RCFLAGS)
LIBDIR_RELEASE_MYSQL = $(LIBDIR)
LIB_RELEASE_MYSQL = $(LIB) -lmysqlclient
LDFLAGS_RELEASE_MYSQL = $(LDFLAGS) -s
OBJDIR_RELEASE_MYSQL = obj/Release_MySQL
DEP_RELEASE_MYSQL = 
OUT_RELEASE_MYSQL = bin/Release_MySQL/SBFspot

OBJ_RELEASE = $(OBJDIR_RELEASE)/boost_ext.o $(OBJDIR_RELEASE)/misc.o $(OBJDIR_RELEASE)/strptime.o $(OBJDIR_RELEASE)/sunrise_sunset.o $(OBJDIR_RELEASE)/SBFNet.o $(OBJDIR_RELEASE)/Bluetooth.o $(OBJDIR_RELEASE)/CSVexport.o $(OBJDIR_RELEASE)/Ethernet.o $(OBJDIR_RELEASE)/EventData.o $(OBJDIR_RELEASE)/ArchData.o $(OBJDIR_RELEASE)/SBFspot.o $(OBJDIR_RELEASE)/TagDefs.o

OBJ_RELEASE_SQLITE = $(OBJDIR_RELEASE_SQLITE)/boost_ext.o $(OBJDIR_RELEASE_SQLITE)/db_SQLite.o $(OBJDIR_RELEASE_SQLITE)/db_SQLite_Export.o $(OBJDIR_RELEASE_SQLITE)/misc.o $(OBJDIR_RELEASE_SQLITE)/strptime.o $(OBJDIR_RELEASE_SQLITE)/sunrise_sunset.o $(OBJDIR_RELEASE_SQLITE)/SBFNet.o $(OBJDIR_RELEASE_SQLITE)/Bluetooth.o $(OBJDIR_RELEASE_SQLITE)/CSVexport.o $(OBJDIR_RELEASE_SQLITE)/Ethernet.o $(OBJDIR_RELEASE_SQLITE)/EventData.o $(OBJDIR_RELEASE_SQLITE)/ArchData.o $(OBJDIR_RELEASE_SQLITE)/SBFspot.o $(OBJDIR_RELEASE_SQLITE)/TagDefs.o

OBJ_RELEASE_MYSQL = $(OBJDIR_RELEASE_MYSQL)/boost_ext.o $(OBJDIR_RELEASE_MYSQL)/db_MySQL.o $(OBJDIR_RELEASE_MYSQL)/db_MySQL_Export.o $(OBJDIR_RELEASE_MYSQL)/misc.o $(OBJDIR_RELEASE_MYSQL)/strptime.o $(OBJDIR_RELEASE_MYSQL)/sunrise_sunset.o $(OBJDIR_RELEASE_MYSQL)/SBFNet.o $(OBJDIR_RELEASE_MYSQL)/Bluetooth.o $(OBJDIR_RELEASE_MYSQL)/CSVexport.o $(OBJDIR_RELEASE_MYSQL)/Ethernet.o $(OBJDIR_RELEASE_MYSQL)/EventData.o $(OBJDIR_RELEASE_MYSQL)/ArchData.o $(OBJDIR_RELEASE_MYSQL)/SBFspot.o $(OBJDIR_RELEASE_MYSQL)/TagDefs.o

all: release release_sqlite release_mysql

clean: clean_release clean_release_sqlite clean_release_mysql

before_release: 
	test -d bin/Release || mkdir -p bin/Release
	test -d $(OBJDIR_RELEASE) || mkdir -p $(OBJDIR_RELEASE)

after_release: 
	cp TagList*.txt bin/Release
	cp date_time_zonespec.csv bin/Release

release: before_release out_release after_release

out_release: before_release $(OBJ_RELEASE) $(DEP_RELEASE)
	$(LD) $(LIBDIR_RELEASE) -o $(OUT_RELEASE) $(OBJ_RELEASE)  $(LDFLAGS_RELEASE) $(LIB_RELEASE)

$(OBJDIR_RELEASE)/boost_ext.o: boost_ext.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c boost_ext.cpp -o $(OBJDIR_RELEASE)/boost_ext.o

$(OBJDIR_RELEASE)/misc.o: misc.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c misc.cpp -o $(OBJDIR_RELEASE)/misc.o

$(OBJDIR_RELEASE)/strptime.o: strptime.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c strptime.cpp -o $(OBJDIR_RELEASE)/strptime.o

$(OBJDIR_RELEASE)/sunrise_sunset.o: sunrise_sunset.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c sunrise_sunset.cpp -o $(OBJDIR_RELEASE)/sunrise_sunset.o

$(OBJDIR_RELEASE)/SBFNet.o: SBFNet.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c SBFNet.cpp -o $(OBJDIR_RELEASE)/SBFNet.o

$(OBJDIR_RELEASE)/Bluetooth.o: Bluetooth.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c Bluetooth.cpp -o $(OBJDIR_RELEASE)/Bluetooth.o

$(OBJDIR_RELEASE)/CSVexport.o: CSVexport.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c CSVexport.cpp -o $(OBJDIR_RELEASE)/CSVexport.o

$(OBJDIR_RELEASE)/Ethernet.o: Ethernet.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c Ethernet.cpp -o $(OBJDIR_RELEASE)/Ethernet.o

$(OBJDIR_RELEASE)/EventData.o: EventData.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c EventData.cpp -o $(OBJDIR_RELEASE)/EventData.o

$(OBJDIR_RELEASE)/ArchData.o: ArchData.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c ArchData.cpp -o $(OBJDIR_RELEASE)/ArchData.o

$(OBJDIR_RELEASE)/SBFspot.o: SBFspot.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c SBFspot.cpp -o $(OBJDIR_RELEASE)/SBFspot.o

$(OBJDIR_RELEASE)/TagDefs.o: TagDefs.cpp
	$(CXX) $(CFLAGS_RELEASE) $(INC_RELEASE) -c TagDefs.cpp -o $(OBJDIR_RELEASE)/TagDefs.o

clean_release: 
	rm -f $(OBJ_RELEASE) $(OUT_RELEASE)
	rm -rf bin/Release
	rm -rf $(OBJDIR_RELEASE)

before_release_sqlite: 
	test -d bin/Release_SQLite || mkdir -p bin/Release_SQLite
	test -d $(OBJDIR_RELEASE_SQLITE) || mkdir -p $(OBJDIR_RELEASE_SQLITE)

after_release_sqlite: 
	cp TagList*.txt bin/Release_SQLite
	cp date_time_zonespec.csv bin/Release_SQLite

install_release_sqlite:
	test -d $(SBFSPOTDIR) || mkdir -p $(SBFSPOTDIR)
	test -f $(SBFSPOTDIR)/SBFspot.cfg || cp SBFspot.cfg $(SBFSPOTDIR)/
	cp -R bin/Release_SQLite/* $(SBFSPOTDIR)/

release_sqlite: before_release_sqlite out_release_sqlite after_release_sqlite

install_sqlite: release_sqlite install_release_sqlite

out_release_sqlite: before_release_sqlite $(OBJ_RELEASE_SQLITE) $(DEP_RELEASE_SQLITE)
	$(LD) $(LIBDIR_RELEASE_SQLITE) -o $(OUT_RELEASE_SQLITE) $(OBJ_RELEASE_SQLITE)  $(LDFLAGS_RELEASE_SQLITE) $(LIB_RELEASE_SQLITE)

$(OBJDIR_RELEASE_SQLITE)/boost_ext.o: boost_ext.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c boost_ext.cpp -o $(OBJDIR_RELEASE_SQLITE)/boost_ext.o

$(OBJDIR_RELEASE_SQLITE)/db_SQLite.o: db_SQLite.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c db_SQLite.cpp -o $(OBJDIR_RELEASE_SQLITE)/db_SQLite.o

$(OBJDIR_RELEASE_SQLITE)/db_SQLite_Export.o: db_SQLite_Export.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c db_SQLite_Export.cpp -o $(OBJDIR_RELEASE_SQLITE)/db_SQLite_Export.o

$(OBJDIR_RELEASE_SQLITE)/misc.o: misc.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c misc.cpp -o $(OBJDIR_RELEASE_SQLITE)/misc.o

$(OBJDIR_RELEASE_SQLITE)/strptime.o: strptime.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c strptime.cpp -o $(OBJDIR_RELEASE_SQLITE)/strptime.o

$(OBJDIR_RELEASE_SQLITE)/sunrise_sunset.o: sunrise_sunset.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c sunrise_sunset.cpp -o $(OBJDIR_RELEASE_SQLITE)/sunrise_sunset.o

$(OBJDIR_RELEASE_SQLITE)/SBFNet.o: SBFNet.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c SBFNet.cpp -o $(OBJDIR_RELEASE_SQLITE)/SBFNet.o

$(OBJDIR_RELEASE_SQLITE)/Bluetooth.o: Bluetooth.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c Bluetooth.cpp -o $(OBJDIR_RELEASE_SQLITE)/Bluetooth.o

$(OBJDIR_RELEASE_SQLITE)/CSVexport.o: CSVexport.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c CSVexport.cpp -o $(OBJDIR_RELEASE_SQLITE)/CSVexport.o

$(OBJDIR_RELEASE_SQLITE)/Ethernet.o: Ethernet.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c Ethernet.cpp -o $(OBJDIR_RELEASE_SQLITE)/Ethernet.o

$(OBJDIR_RELEASE_SQLITE)/EventData.o: EventData.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c EventData.cpp -o $(OBJDIR_RELEASE_SQLITE)/EventData.o

$(OBJDIR_RELEASE_SQLITE)/ArchData.o: ArchData.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c ArchData.cpp -o $(OBJDIR_RELEASE_SQLITE)/ArchData.o

$(OBJDIR_RELEASE_SQLITE)/SBFspot.o: SBFspot.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c SBFspot.cpp -o $(OBJDIR_RELEASE_SQLITE)/SBFspot.o

$(OBJDIR_RELEASE_SQLITE)/TagDefs.o: TagDefs.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c TagDefs.cpp -o $(OBJDIR_RELEASE_SQLITE)/TagDefs.o

clean_release_sqlite: 
	rm -f $(OBJ_RELEASE_SQLITE) $(OUT_RELEASE_SQLITE)
	rm -rf bin/Release_SQLite
	rm -rf $(OBJDIR_RELEASE_SQLITE)

before_release_mysql: 
	test -d bin/Release_MySQL || mkdir -p bin/Release_MySQL
	test -d $(OBJDIR_RELEASE_MYSQL) || mkdir -p $(OBJDIR_RELEASE_MYSQL)

after_release_mysql: 
	cp TagList*.txt bin/Release_MySQL
	cp date_time_zonespec.csv bin/Release_MySQL

release_mysql: before_release_mysql out_release_mysql after_release_mysql

install_mysql: release_mysql install_release_mysql

out_release_mysql: before_release_mysql $(OBJ_RELEASE_MYSQL) $(DEP_RELEASE_MYSQL)
	$(LD) $(LIBDIR_RELEASE_MYSQL) -o $(OUT_RELEASE_MYSQL) $(OBJ_RELEASE_MYSQL)  $(LDFLAGS_RELEASE_MYSQL) $(LIB_RELEASE_MYSQL)

$(OBJDIR_RELEASE_MYSQL)/boost_ext.o: boost_ext.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c boost_ext.cpp -o $(OBJDIR_RELEASE_MYSQL)/boost_ext.o

$(OBJDIR_RELEASE_MYSQL)/db_MySQL.o: db_MySQL.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c db_MySQL.cpp -o $(OBJDIR_RELEASE_MYSQL)/db_MySQL.o

$(OBJDIR_RELEASE_MYSQL)/db_MySQL_Export.o: db_MySQL_Export.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c db_MySQL_Export.cpp -o $(OBJDIR_RELEASE_MYSQL)/db_MySQL_Export.o

$(OBJDIR_RELEASE_MYSQL)/misc.o: misc.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c misc.cpp -o $(OBJDIR_RELEASE_MYSQL)/misc.o

$(OBJDIR_RELEASE_MYSQL)/strptime.o: strptime.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c strptime.cpp -o $(OBJDIR_RELEASE_MYSQL)/strptime.o

$(OBJDIR_RELEASE_MYSQL)/sunrise_sunset.o: sunrise_sunset.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c sunrise_sunset.cpp -o $(OBJDIR_RELEASE_MYSQL)/sunrise_sunset.o

$(OBJDIR_RELEASE_MYSQL)/SBFNet.o: SBFNet.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c SBFNet.cpp -o $(OBJDIR_RELEASE_MYSQL)/SBFNet.o

$(OBJDIR_RELEASE_MYSQL)/Bluetooth.o: Bluetooth.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c Bluetooth.cpp -o $(OBJDIR_RELEASE_MYSQL)/Bluetooth.o

$(OBJDIR_RELEASE_MYSQL)/CSVexport.o: CSVexport.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c CSVexport.cpp -o $(OBJDIR_RELEASE_MYSQL)/CSVexport.o

$(OBJDIR_RELEASE_MYSQL)/Ethernet.o: Ethernet.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c Ethernet.cpp -o $(OBJDIR_RELEASE_MYSQL)/Ethernet.o

$(OBJDIR_RELEASE_MYSQL)/EventData.o: EventData.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c EventData.cpp -o $(OBJDIR_RELEASE_MYSQL)/EventData.o

$(OBJDIR_RELEASE_MYSQL)/ArchData.o: ArchData.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c ArchData.cpp -o $(OBJDIR_RELEASE_MYSQL)/ArchData.o

$(OBJDIR_RELEASE_MYSQL)/SBFspot.o: SBFspot.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c SBFspot.cpp -o $(OBJDIR_RELEASE_MYSQL)/SBFspot.o

$(OBJDIR_RELEASE_MYSQL)/TagDefs.o: TagDefs.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c TagDefs.cpp -o $(OBJDIR_RELEASE_MYSQL)/TagDefs.o

clean_release_mysql: 
	rm -f $(OBJ_RELEASE_MYSQL) $(OUT_RELEASE_MYSQL)
	rm -rf bin/Release_MySQL
	rm -rf $(OBJDIR_RELEASE_MYSQL)

install_release_mysql:
	test -d bin/Release_MySQL || release_mysql
	test -d $(SBFSPOTDIR) || mkdir -p $(SBFSPOTDIR)
	test -f $(SBFSPOTDIR)/SBFspot.cfg || cp SBFspot.cfg $(SBFSPOTDIR)/
	cp -R bin/Release_MySQL/* $(SBFSPOTDIR)/

.PHONY: before_release after_release clean_release \
before_release_sqlite after_release_sqlite clean_release_sqlite \
before_release_mysql after_release_mysql clean_release_mysql

