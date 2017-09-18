
public class BoardObject {
	String name;
	boolean team;
	int positionX;
	int positionY;
	
	BoardObject(String team, int x, int y) {
		this.positionX = x;
		this.positionY = y;
		if (team.equals("BLACK")) this.team = false;
		else this.team = true;
	}
	
	void printTeam() {
		System.out.println("TEAM: " + ((this.team) ? "WHITE" : "BLACK"));
	}

	public int getPositionX () {
		return this.positionX;
	}
	public void setPositionX (int x) {
		 this.positionX = x;
		 return;
	}
	public int getPositionY () {
		return this.positionY;
	}
	public void setPositionY (int y) {
		this.positionY = y;
		return;
	}
	
	public boolean[] getAvailableMoves() {
		boolean[] availableMoves = new boolean[4];
		if ((this.positionX - 1) >= 0) {
			availableMoves[0] = true;
		}
		if ((this.positionX + 1) <= 7){
			availableMoves[1] = true;
		}
		if ((this.positionY - 1) >= 0) {
			availableMoves[2] = true;
		}
		if ((this.positionY + 1) <= 7) {
			availableMoves[3] = true;
		}
		
		return availableMoves;
	}
	
	
	
}
