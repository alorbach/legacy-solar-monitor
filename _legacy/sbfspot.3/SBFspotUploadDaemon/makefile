######################################
# Makefile for SBFspotUploadDaemon
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
LIB = 
LDFLAGS = 

INC_RELEASE_SQLITE = $(INC) -I../SBFspot
CFLAGS_RELEASE_SQLITE = $(CFLAGS) -O2 -DUSE_SQLITE
CFLAGS_RELEASE_SQLITE_NOOPT = $(CFLAGS) -DUSE_SQLITE
RESINC_RELEASE_SQLITE = $(RESINC)
RCFLAGS_RELEASE_SQLITE = $(RCFLAGS)
LIBDIR_RELEASE_SQLITE = $(LIBDIR)
LIB_RELEASE_SQLITE = $(LIB) -lcurl -lsqlite3
LDFLAGS_RELEASE_SQLITE = $(LDFLAGS) -s
OBJDIR_RELEASE_SQLITE = obj/Release_SQLite
DEP_RELEASE_SQLITE = 
OUT_RELEASE_SQLITE = bin/Release_SQLite/SBFspotUploadDaemon

INC_RELEASE_MYSQL = $(INC) -I../SBFspot
CFLAGS_RELEASE_MYSQL = $(CFLAGS) -O2 -DUSE_MYSQL
CFLAGS_RELEASE_MYSQL_NOOPT = $(CFLAGS) -DUSE_MYSQL
RESINC_RELEASE_MYSQL = $(RESINC)
RCFLAGS_RELEASE_MYSQL = $(RCFLAGS)
LIBDIR_RELEASE_MYSQL = $(LIBDIR)
LIB_RELEASE_MYSQL = $(LIB) -lcurl -lmysqlclient
LDFLAGS_RELEASE_MYSQL = $(LDFLAGS) -s
OBJDIR_RELEASE_MYSQL = obj/Release_MySQL
DEP_RELEASE_MYSQL = 
OUT_RELEASE_MYSQL = bin/Release_MySQL/SBFspotUploadDaemon

OBJ_RELEASE_SQLITE = $(OBJDIR_RELEASE_SQLITE)/SBFspot/db_SQLite.o \
$(OBJDIR_RELEASE_SQLITE)/main.o \
$(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/Configuration.o \
$(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/PVOutput.o \
$(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/CommonServiceCode.o \
$(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/PVOutput_x.o

OBJ_RELEASE_MYSQL = $(OBJDIR_RELEASE_MYSQL)/SBFspot/db_MySQL.o \
$(OBJDIR_RELEASE_MYSQL)/main.o \
$(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/Configuration.o \
$(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/PVOutput.o \
$(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/CommonServiceCode.o \
$(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/PVOutput_x.o \

clean: clean_release_sqlite clean_release_mysql

before_release_sqlite: 
	test -d bin/Release_SQLite || mkdir -p bin/Release_SQLite
	test -d $(OBJDIR_RELEASE_SQLITE)/SBFspot || mkdir -p $(OBJDIR_RELEASE_SQLITE)/SBFspot
	test -d $(OBJDIR_RELEASE_SQLITE) || mkdir -p $(OBJDIR_RELEASE_SQLITE)
	test -d $(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon || mkdir -p $(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon

after_release_sqlite: 

install_release_sqlite:
	test -d $(SBFSPOTDIR) || mkdir -p $(SBFSPOTDIR)
	test -f $(SBFSPOTDIR)/SBFspotUpload.cfg || cp SBFspotUpload.cfg $(SBFSPOTDIR)/
	cp -R bin/Release_SQLite/* $(SBFSPOTDIR)/

release_sqlite: before_release_sqlite out_release_sqlite after_release_sqlite

install_sqlite: release_sqlite install_release_sqlite

out_release_sqlite: before_release_sqlite $(OBJ_RELEASE_SQLITE) $(DEP_RELEASE_SQLITE)
	$(LD) $(LIBDIR_RELEASE_SQLITE) -o $(OUT_RELEASE_SQLITE) $(OBJ_RELEASE_SQLITE)  $(LDFLAGS_RELEASE_SQLITE) $(LIB_RELEASE_SQLITE)

$(OBJDIR_RELEASE_SQLITE)/SBFspot/db_SQLite.o: ../SBFspot/db_SQLite.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c ../SBFspot/db_SQLite.cpp -o $(OBJDIR_RELEASE_SQLITE)/SBFspot/db_SQLite.o

$(OBJDIR_RELEASE_SQLITE)/main.o: main.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c main.cpp -o $(OBJDIR_RELEASE_SQLITE)/main.o

$(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/Configuration.o: ../SBFspotUploadCommon/Configuration.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c ../SBFspotUploadCommon/Configuration.cpp -o $(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/Configuration.o

$(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/PVOutput.o: ../SBFspotUploadCommon/PVOutput.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c ../SBFspotUploadCommon/PVOutput.cpp -o $(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/PVOutput.o

$(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/CommonServiceCode.o: ../SBFspotUploadCommon/CommonServiceCode.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE) $(INC_RELEASE_SQLITE) -c ../SBFspotUploadCommon/CommonServiceCode.cpp -o $(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/CommonServiceCode.o

$(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/PVOutput_x.o: ../SBFspotUploadCommon/PVOutput_x.cpp
	$(CXX) $(CFLAGS_RELEASE_SQLITE_NOOPT) $(INC_RELEASE_SQLITE) -c ../SBFspotUploadCommon/PVOutput_x.cpp -o $(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon/PVOutput_x.o

clean_release_sqlite: 
	rm -f $(OBJ_RELEASE_SQLITE) $(OUT_RELEASE_SQLITE)
	rm -rf bin/Release_SQLite
	rm -rf $(OBJDIR_RELEASE_SQLITE)/SBFspot
	rm -rf $(OBJDIR_RELEASE_SQLITE)
	rm -rf $(OBJDIR_RELEASE_SQLITE)/SBFspotUploadCommon

before_release_mysql: 
	test -d bin/Release_MySQL || mkdir -p bin/Release_MySQL
	test -d $(OBJDIR_RELEASE_MYSQL)/SBFspot || mkdir -p $(OBJDIR_RELEASE_MYSQL)/SBFspot
	test -d $(OBJDIR_RELEASE_MYSQL) || mkdir -p $(OBJDIR_RELEASE_MYSQL)
	test -d $(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon || mkdir -p $(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon

after_release_mysql: 

release_mysql: before_release_mysql out_release_mysql after_release_mysql

install_mysql: release_mysql install_release_mysql

out_release_mysql: before_release_mysql $(OBJ_RELEASE_MYSQL) $(DEP_RELEASE_MYSQL)
	$(LD) $(LIBDIR_RELEASE_MYSQL) -o $(OUT_RELEASE_MYSQL) $(OBJ_RELEASE_MYSQL) $(LDFLAGS_RELEASE_MYSQL) $(LIB_RELEASE_MYSQL)

$(OBJDIR_RELEASE_MYSQL)/SBFspot/db_MySQL.o: ../SBFspot/db_MySQL.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c ../SBFspot/db_MySQL.cpp -o $(OBJDIR_RELEASE_MYSQL)/SBFspot/db_MySQL.o

$(OBJDIR_RELEASE_MYSQL)/main.o: main.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c main.cpp -o $(OBJDIR_RELEASE_MYSQL)/main.o

$(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/Configuration.o: ../SBFspotUploadCommon/Configuration.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c ../SBFspotUploadCommon/Configuration.cpp -o $(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/Configuration.o

$(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/PVOutput.o: ../SBFspotUploadCommon/PVOutput.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c ../SBFspotUploadCommon/PVOutput.cpp -o $(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/PVOutput.o

$(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/CommonServiceCode.o: ../SBFspotUploadCommon/CommonServiceCode.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL) $(INC_RELEASE_MYSQL) -c ../SBFspotUploadCommon/CommonServiceCode.cpp -o $(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/CommonServiceCode.o

$(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/PVOutput_x.o: ../SBFspotUploadCommon/PVOutput_x.cpp
	$(CXX) $(CFLAGS_RELEASE_MYSQL_NOOPT) $(INC_RELEASE_MYSQL) -c ../SBFspotUploadCommon/PVOutput_x.cpp -o $(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon/PVOutput_x.o

clean_release_mysql: 
	rm -f $(OBJ_RELEASE_MYSQL) $(OUT_RELEASE_MYSQL)
	rm -rf bin/Release_MySQL
	rm -rf $(OBJDIR_RELEASE_MYSQL)/SBFspot
	rm -rf $(OBJDIR_RELEASE_MYSQL)
	rm -rf $(OBJDIR_RELEASE_MYSQL)/SBFspotUploadCommon

install_release_mysql:
	test -d bin/Release_MySQL || release_mysql
	test -d $(SBFSPOTDIR) || mkdir -p $(SBFSPOTDIR)
	test -f $(SBFSPOTDIR)/SBFspotUpload.cfg || cp SBFspotUpload.cfg $(SBFSPOTDIR)/
	cp -R bin/Release_MySQL/* $(SBFSPOTDIR)/

.PHONY: before_release_sqlite after_release_sqlite clean_release_sqlite \
before_release_mysql after_release_mysql clean_release_mysql
